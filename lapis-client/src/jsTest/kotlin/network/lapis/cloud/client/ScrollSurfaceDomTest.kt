package network.lapis.cloud.client

import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The scroll containers the V1.9.68 rule (R59, "one scroll surface") still allows -- the DOM-side twin of `ScrollSurfaceTripwireTest`'s
 * `CSS_LEDGER`: a fixed/absolute overlay or modal (E1), a popup list (E2), a log (E3), the fullscreen surfaces (E0), plus the two
 * Bootstrap components that bring their own scroller (`.modal`, `.offcanvas-body`, both modal surfaces).
 */
internal val ALLOWED_SCROLLER_SELECTORS =
    listOf(
        ".lapis-conference-chat-log",
        ".lapis-encounter-chat-log",
        ".lapis-ssel-dropdown",
        ".lapis-member-map-search-results",
        ".lapis-encounter-side",
        ".lapis-conference-roster",
        ".lapis-conference-chat",
        ".lapis-conference-voting",
        ".lapis-conference-more-sheet",
        ".lapis-encounter-more-sheet",
        ".lapis-conference-call-panel.lapis-conference-fullscreen",
        ".lapis-encounter-room.is-pseudo-fullscreen",
        ".lapis-conference-filmstrip",
        ".modal",
        ".offcanvas-body",
    )

/**
 * Every descendant of [root] that really is a scroller in the real cascade: `overflow-y` is `auto`/`scroll` AND its content really
 * overflows (`scrollHeight > clientHeight + 1`) -- a computed `overflow-y: auto` on a box that never overflows is harmless and not
 * reported -- minus the allowed ones ([ALLOWED_SCROLLER_SELECTORS]). Returns a readable description per offender.
 */
internal fun forbiddenScrollers(root: HTMLElement): List<String> =
    // V1.9.95: `*` also matches SVG elements (the blessing's cross), which are no HTMLElement and never a scroller of their own.
    (0 until root.querySelectorAll("*").length)
        .mapNotNull { root.querySelectorAll("*").item(it) as? HTMLElement }
        .filter { element ->
            val overflowY = window.getComputedStyle(element).overflowY
            (overflowY == "auto" || overflowY == "scroll") &&
                element.scrollHeight > element.clientHeight + 1 &&
                ALLOWED_SCROLLER_SELECTORS.none { element.matches(it) }
        }.map { "${it.tagName.lowercase()}.${it.className} (${it.scrollHeight} > ${it.clientHeight})" }

/**
 * R59 in a real Chrome with the real stylesheets (Bootstrap + KVision + `theme.css`), measured on the computed style -- never on the CSS
 * text (that is `ScrollSurfaceTripwireTest`'s job). Honest limits: the whole conference screen and the whole app shell cannot be mounted in
 * Karma, so the conference and sidebar parts are probes that carry the production classes; the media queries follow the real Karma window,
 * not an emulated phone; Safari/iOS (`dvh`, rubber-banding, `overscroll-behavior` before iOS 16) is not covered.
 */
class ScrollSurfaceDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                true
            }
    }

    private fun probe(
        host: HTMLElement,
        className: String,
        tag: String = "div",
    ): HTMLElement {
        val element = document.createElement(tag) as HTMLElement
        element.className = className
        host.appendChild(element)
        return element
    }

    private fun style(element: HTMLElement) = window.getComputedStyle(element)

    private fun <T> withHost(block: (HTMLElement) -> T): T {
        assertTrue(stylesLoaded)
        val host = document.createElement("div") as HTMLElement
        document.body!!.appendChild(host)
        try {
            return block(host)
        } finally {
            host.remove()
        }
    }

    @Test
    fun pageSurfaces_growWithTheirContent_theyDoNotScroll() {
        withHost { host ->
            val tall = "<div style=\"height:900px\"></div>"
            listOf(
                "lapis-conference-grid",
                "lapis-mailing-editor",
                "lapis-encounter-benches-frame",
                "lapis-sidebar-nav",
                "lapis-legal-text",
                "lapis-member-map-table-panel",
            ).forEach { cls ->
                val element = probe(host, cls)
                element.innerHTML = tall
                val computed = style(element)
                assertFalse(computed.overflowY == "auto" || computed.overflowY == "scroll", "$cls overflow-y is ${computed.overflowY}")
                assertFalse(computed.overflowX == "auto" || computed.overflowX == "scroll", "$cls overflow-x is ${computed.overflowX}")
                assertEquals("none", computed.maxHeight, "$cls has no max-height")
                assertTrue(
                    element.getBoundingClientRect().height >= 900,
                    "$cls grew to its content: ${element.getBoundingClientRect().height}",
                )
            }
        }
    }

    @Test
    fun mediaAndLegalText_areNotScrollers() {
        withHost { host ->
            listOf("lapis-conference-share-media" to "video", "lapis-recording-player" to "video").forEach { (cls, tag) ->
                val computed = style(probe(host, cls, tag))
                assertFalse(computed.overflowY == "auto" || computed.overflowY == "scroll", "$cls is media, not a scroller")
            }
            // The legal text is read in full: no height cap of any kind.
            val legal = probe(host, "border rounded p-2 mb-2 lapis-legal-text")
            assertEquals("none", style(legal).maxHeight)
            assertEquals("visible", style(legal).overflowY)
        }
    }

    @Test
    fun theLedgerScrollers_containTheirScroll() {
        withHost { host ->
            listOf(
                "lapis-conference-chat-log",
                "lapis-encounter-chat-log",
                "lapis-ssel-dropdown",
                "lapis-member-map-search-results",
            ).forEach { cls ->
                val computed = style(probe(host, cls))
                assertEquals("auto", computed.overflowY, "$cls scrolls (a log / popup is the exception)")
                assertEquals("contain", computed.getPropertyValue("overscroll-behavior-y"), "$cls must not chain into the page")
            }
            val strip = style(probe(host, "lapis-conference-filmstrip"))
            assertEquals("auto", strip.overflowX)
            assertEquals("hidden", strip.overflowY, "a horizontal strip never scrolls vertically")
            assertEquals("contain", strip.getPropertyValue("overscroll-behavior-x"))
        }
    }

    @Test
    fun theChatLogs_haveABoundedHeight() {
        withHost { host ->
            val log = probe(host, "lapis-conference-chat-log")
            log.innerHTML = "<div style=\"height:3000px\"></div>"
            val height = log.getBoundingClientRect().height
            assertTrue(height in 159.0..321.0, "conference chat log is clamp(160px, 30vh, 320px) high: $height")
            assertTrue(log.scrollHeight > log.clientHeight, "the log scrolls inside its own bounded box")
        }
    }

    @Test
    fun theSidebar_hasNoScrollerOfItsOwnOnDesktop() {
        withHost { host ->
            val sidebar = probe(host, "lapis-sidebar offcanvas-lg show")
            val nav = probe(sidebar, "lapis-sidebar-nav")
            nav.innerHTML = "<div style=\"height:2000px;width:500px\"></div>"
            assertEquals("visible", style(nav).overflowX, "the nav no longer scrolls sideways")
            assertEquals("visible", style(nav).overflowY)
            if (window.innerWidth >= 992) {
                val computed = style(sidebar)
                assertEquals("static", computed.position, "the sidebar scrolls with the page")
                assertFalse(
                    computed.overflowY == "auto" || computed.overflowY == "scroll",
                    "no scrollbar of its own: ${computed.overflowY}",
                )
                assertEquals("none", computed.maxHeight)
            }
        }
    }

    @Test
    fun theBannerScrollPadding_keepsFocusedElementsBelowTheStickyBanner() {
        assertTrue(stylesLoaded)
        val padding = window.getComputedStyle(document.documentElement!!).getPropertyValue("scroll-padding-top")
        assertTrue(padding.endsWith("px") && padding.removeSuffix("px").toDouble() > 0.0, "scroll-padding-top is set on html: '$padding'")
    }

    @Test
    fun aLongDataTable_atEveryWidth_hasNoScrollerOfItsOwn() {
        data class Row(
            val name: String,
            val amount: String,
        )
        val rows = (1..80).map { Row("Mitglied $it", "$it,00") }
        listOf(360, 768, 1280).forEach { width ->
            withMountedRoot("scroll-surface-table-$width") { root, element ->
                val host = element()
                host.style.width = "${width}px"
                root.dataTableWith(
                    columns =
                        listOf(
                            textColumn<Row>(title = tr("Name"), primary = true) { it.name },
                            textColumn<Row>(title = tr("Betrag"), numeric = true) { it.amount },
                        ),
                    rows = rows,
                    sort = null,
                    onSort = null,
                    sortOptions = SortOptions(),
                    actions = null,
                    focusSortKey = null,
                    viewport = FakeNarrowViewport(narrow = width < 768),
                )
                val inner = host.allOf("tr, .lapis-data-card").size
                assertTrue(inner >= 80, "all 80 rows are in the DOM at ${width}px: $inner")
                assertEquals(emptyList(), forbiddenScrollers(host), "no scroller of its own at ${width}px")
                assertTrue(
                    host.scrollWidth <= host.clientWidth + 1,
                    "no sideways scroll at ${width}px: ${host.scrollWidth} > ${host.clientWidth}",
                )
            }
        }
    }
}
