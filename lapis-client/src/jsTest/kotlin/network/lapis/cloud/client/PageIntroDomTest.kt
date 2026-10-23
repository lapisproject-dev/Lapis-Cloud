package network.lapis.cloud.client

import io.kvision.html.div
import io.kvision.i18n.tr
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.coroutines.delay
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.187/V1.9.190: the page intro in a REAL mounted root. Pins placement under the title, plain-text rendering, the accessible names, collapsing
 * (storage key, focus hand-over, persistence, v1 migration), a throwing storage, the help link rule (only for topics that exist), the tab order,
 * the touch target and the absence of any transition or animation.
 */
class PageIntroDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('@fortawesome/fontawesome-free/css/all.css')")
                js("require('./theme.css')")
                true
            }
        const val KEY = "lapis.pageIntro.v2.t-view"
        const val KEY_V1 = "lapis.pageIntro.v1.t-view"
    }

    @BeforeTest
    fun setUp() {
        assertTrue(stylesLoaded)
        PageIntroStorage.expandAll()
    }

    @AfterTest
    fun tearDown() {
        PageIntroStorage.expandAll()
    }

    private fun HTMLElement.intro(): HTMLElement? = querySelector("section.page-intro") as HTMLElement?

    @Test
    fun intro_sitsInTheHeaderDirectlyAfterTheTitleRow_andIsPlainText() {
        withMountedRoot("page-intro-placement") { root, element ->
            root.pageHeader(tr("Beiträge"), intro = PageIntroDef("t-view", "Erste <b>fette</b> Zeile."))
            val intro = element().querySelector(".lapis-page-header > section.page-intro") as HTMLElement
            val titleRow = intro.previousElementSibling as HTMLElement
            assertNotNull(titleRow.querySelector("h1#$PAGE_TITLE_ID"), "the intro follows the title row")
            assertEquals(0, intro.querySelectorAll("b").length, "a tag in the text is text, not markup")
            assertTrue(intro.textContent.orEmpty().contains("<b>fette</b>"))
        }
    }

    @Test
    fun intro_withASubtitle_comesAfterTheSubtitle() {
        withMountedRoot("page-intro-subtitle") { root, element ->
            root.pageHeader(tr("Dashboard"), subtitle = "Willkommen", intro = PageIntroDef("t-view", "Text."))
            val intro = element().intro()!!
            assertTrue((intro.previousElementSibling as HTMLElement).textContent.orEmpty().contains("Willkommen"))
        }
    }

    @Test
    fun throughTheHeader_theRegionIsLabelledByTheTitle_directCallerGetsAnAriaLabel() {
        withMountedRoot("page-intro-label") { root, element ->
            root.pageHeader(tr("Beiträge"), intro = PageIntroDef("t-view", "Text."))
            assertEquals(PAGE_TITLE_ID, element().intro()!!.getAttribute("aria-labelledby"))
            assertNotNull(document.getElementById(PAGE_TITLE_ID), "the labelling target exists")
        }
        withMountedRoot("page-intro-label-direct") { root, element ->
            root.div { pageIntro("t-direct", "Text.") }
            val intro = element().intro()!!
            assertEquals("Einführung", intro.getAttribute("aria-label"))
            assertNull(intro.getAttribute("aria-labelledby"))
        }
    }

    @Test
    fun collapseButton_hasTheFullAccessibleName_containingItsVisibleLabel() {
        withMountedRoot("page-intro-close-name") { root, element ->
            root.pageHeader(tr("Beiträge"), intro = PageIntroDef("t-view", "Text."))
            val collapse = element().querySelector("section.page-intro button.page-intro__collapse") as HTMLElement
            assertEquals("Einführung einklappen", collapse.getAttribute("aria-label"))
            assertEquals("Einführung einklappen", collapse.getAttribute("title"))
            assertTrue(
                collapse.textContent.orEmpty().contains("Einklappen", ignoreCase = true),
                "R58: a visible label, not an icon-only button",
            )
            assertTrue(collapse.getAttribute("aria-label").orEmpty().contains(collapse.textContent.orEmpty().trim(), ignoreCase = true))
            assertEquals("true", collapse.getAttribute("aria-expanded"))
            val icon = collapse.querySelector("i") as HTMLElement
            assertEquals("true", icon.getAttribute("aria-hidden"))
            val body = element().querySelector(".page-intro__body") as HTMLElement
            assertEquals("page-intro-t-view-body", body.id)
            assertEquals(body.id, collapse.getAttribute("aria-controls"))
        }
    }

    @Test
    fun defaultIsOpen() {
        withMountedRoot("page-intro-default") { root, element ->
            root.pageHeader(tr("Beiträge"), intro = PageIntroDef("t-view", "Text."))
            assertNotNull(element().querySelector(".page-intro__text"))
            assertNull(element().querySelector(".page-intro__toggle"))
        }
    }

    @Test
    fun collapseButton_touchTargetIsAtLeast32px() {
        withMountedRoot("page-intro-target") { root, element ->
            root.pageHeader(tr("Beiträge"), intro = PageIntroDef("t-view", "Text."))
            val close = element().querySelector("button.page-intro__collapse") as HTMLElement
            val box = close.getBoundingClientRect()
            assertTrue(box.width >= 31.5 && box.height >= 31.5, "target ${box.width} x ${box.height}")
        }
    }

    private fun HTMLElement.toggle(): HTMLElement? = querySelector("button.page-intro__toggle") as HTMLElement?

    @Test
    fun collapse_storesTheV2Key_showsOnlyTheToggle_andFocusesIt(): Promise<Unit> =
        formTest {
            withMountedRoot("page-intro-hide") { root, element ->
                root.pageHeader(tr("Beiträge"), intro = PageIntroDef("t-view", "Text."))
                (element().querySelector("button.page-intro__collapse") as HTMLElement).click()
                delay(120)
                assertNotNull(element().intro(), "the section stays")
                assertNull(element().querySelector(".page-intro__text"), "the text is gone")
                assertEquals("collapsed", localStorage.getItem(KEY))
                val toggle = assertNotNull(element().toggle())
                assertEquals("false", toggle.getAttribute("aria-expanded"))
                assertTrue(toggle.textContent.orEmpty().contains("Hinweis anzeigen"))
                assertNull(toggle.getAttribute("aria-label"), "the visible text is the name")
                assertEquals("Einführung dieser Seite anzeigen", toggle.getAttribute("title"))
                assertTrue(toggle.querySelectorAll("[aria-hidden=true]").length >= 2, "info and chevron icons are decoration")
                assertEquals(toggle, document.activeElement, "the focus lands on the toggle")
            }
        }

    @Test
    fun expand_removesTheKeys_andFocusesTheBody(): Promise<Unit> =
        formTest {
            localStorage.setItem(KEY, "collapsed")
            withMountedRoot("page-intro-expand") { root, element ->
                root.pageHeader(tr("Beiträge"), intro = PageIntroDef("t-view", "Text.", more = "Mehr Text."))
                element().toggle()!!.click()
                delay(150)
                assertNull(localStorage.getItem(KEY))
                assertNull(element().toggle())
                val body = element().querySelector(".page-intro__body") as HTMLElement
                assertEquals(body, document.activeElement, "the focus lands on the intro body")
                assertNotNull(element().querySelector("details.page-intro__more"), "details work after expanding")
            }
        }

    @Test
    fun collapsedKey_meansOnlyTheToggle_afterARerender() {
        localStorage.setItem(KEY, "collapsed")
        withMountedRoot("page-intro-persist") { root, element ->
            root.pageHeader(tr("Beiträge"), intro = PageIntroDef("t-view", "Text."))
            assertNotNull(element().toggle())
            assertNull(element().querySelector(".page-intro__text"))
            assertNotNull(element().querySelector("h1"), "the title is still there")
        }
    }

    @Test
    fun v1Hidden_isReadAsCollapsed_andMigrated() {
        localStorage.setItem(KEY_V1, "hidden")
        assertTrue(PageIntroStorage.anyCollapsed(), "an unmigrated v1 key counts")
        withMountedRoot("page-intro-migrate") { root, element ->
            root.pageHeader(tr("Beiträge"), intro = PageIntroDef("t-view", "Text."))
            assertNotNull(element().toggle())
        }
        assertEquals("collapsed", localStorage.getItem(KEY))
        assertNull(localStorage.getItem(KEY_V1))
    }

    @Test
    fun expandAll_removesV1AndV2Keys() {
        localStorage.setItem(KEY_V1, "hidden")
        localStorage.setItem("lapis.pageIntro.v2.other", "collapsed")
        PageIntroStorage.expandAll()
        assertNull(localStorage.getItem(KEY_V1))
        assertNull(localStorage.getItem("lapis.pageIntro.v2.other"))
        assertFalse(PageIntroStorage.anyCollapsed())
    }

    @Test
    fun expand_alsoRemovesTheV1Key_soItIsNotMigratedBack() {
        localStorage.setItem(KEY_V1, "hidden")
        PageIntroStorage.expand("t-view")
        assertFalse(PageIntroStorage.isCollapsed("t-view"))
    }

    @Test
    fun throwingStorage_showsTheIntro_collapsesForTheSession_andNeverThrows(): Promise<Unit> =
        formTest {
            val proto = js("Storage.prototype")
            val originalGet = proto.getItem
            val originalSet = proto.setItem
            val originalKey = proto.key
            val originalRemove = proto.removeItem
            val boom = js("(function () { throw new Error('storage blocked'); })")
            try {
                proto.getItem = boom
                proto.setItem = boom
                proto.key = boom
                proto.removeItem = boom
                withMountedRoot("page-intro-throwing") { root, element ->
                    root.pageHeader(tr("Beiträge"), intro = PageIntroDef("t-view", "Text."))
                    assertNotNull(element().querySelector(".page-intro__text"), "a blocked storage must not collapse the intro")
                    (element().querySelector("button.page-intro__collapse") as HTMLElement).click()
                    delay(60)
                    assertNotNull(element().toggle(), "the collapse works for this page life")
                }
                withMountedRoot("page-intro-throwing-2") { root, element ->
                    root.pageHeader(tr("Beiträge"), intro = PageIntroDef("t-view", "Text."))
                    assertNotNull(element().toggle(), "...and a re-render of the same view keeps it collapsed")
                    assertTrue(PageIntroStorage.anyCollapsed())
                }
            } finally {
                proto.getItem = originalGet
                proto.setItem = originalSet
                proto.key = originalKey
                proto.removeItem = originalRemove
            }
        }

    @Test
    fun helpLink_existsOnlyForATopicThatHasAPage() {
        withMountedRoot("page-intro-link") { root, element ->
            root.div(className = "with") { pageIntro("t-with", "Text.", helpTopic = "travel-expenses") }
            root.div(className = "unknown") { pageIntro("t-unknown", "Text.", helpTopic = "no-such-topic") }
            root.div(className = "none") { pageIntro("t-none", "Text.", helpTopic = null) }
            val link = element().querySelector(".with a") as HTMLElement
            assertEquals(Branding.helpUrl("travel-expenses"), link.getAttribute("href"))
            assertEquals("_blank", link.getAttribute("target"))
            val rel = link.getAttribute("rel").orEmpty()
            assertTrue(rel.contains("noopener") && rel.contains("noreferrer"), rel)
            assertTrue(link.textContent.orEmpty().contains("Mehr in der Hilfe"))
            assertEquals(
                "true",
                (link.querySelector("[aria-hidden]") as HTMLElement).getAttribute("aria-hidden"),
                "the arrow is decoration",
            )
            assertNull(element().querySelector(".unknown a"), "no link for a topic without a page")
            assertNull(element().querySelector(".none a"), "no link without a topic")
            assertNull(element().querySelector(".unknown [href]"))
        }
    }

    @Test
    fun tabOrder_isHelpLink_thenCollapseButton() {
        withMountedRoot("page-intro-tab") { root, element ->
            root.div { pageIntro("t-tab", "Text.", helpTopic = "travel-expenses") }
            val link = element().querySelector("section.page-intro a") as HTMLElement
            val button = element().querySelector("section.page-intro button") as HTMLElement
            assertTrue(
                (link.compareDocumentPosition(button).toInt() and 4) != 0,
                "the button follows the link in the DOM (DOM order is tab order, no tabindex)",
            )
            assertNull(link.getAttribute("tabindex"))
            assertNull(button.getAttribute("tabindex"))
        }
    }

    @Test
    fun intro_hasNoTransitionAndNoAnimation() {
        withMountedRoot("page-intro-motion") { root, element ->
            root.pageHeader(tr("Beiträge"), intro = PageIntroDef("t-view", "Text."))
            val style = kotlinx.browser.window.getComputedStyle(element().intro()!!)
            assertEquals("none", style.getPropertyValue("animation-name"))
            assertTrue(style.getPropertyValue("transition-duration").split(",").all { it.trim() == "0s" })
            assertFalse(style.getPropertyValue("display") == "none")
        }
    }

    // ---- V1.9.188: the optional "Mehr" part ----------------------------------------------------------------------------------------

    @Test
    fun more_isAClosedDetails_withAMehrSummary_andPlainText() {
        withMountedRoot("page-intro-more") { root, element ->
            root.pageHeader(tr("Beiträge"), intro = PageIntroDef("t-view", "Text.", more = "Mehr <i>Text</i>."))
            val details = element().querySelector("section.page-intro details.page-intro__more") as HTMLElement
            assertFalse(details.hasAttribute("open"), "closed on every render")
            assertEquals("Mehr", (details.querySelector("summary") as HTMLElement).textContent?.trim())
            assertEquals(0, details.querySelectorAll("i").length, "a tag in the more text is text, not markup")
            assertTrue(details.textContent.orEmpty().contains("<i>Text</i>"))
            assertNull(localStorage.getItem(KEY), "the open state is not stored (and nothing else either)")
        }
    }

    @Test
    fun withoutMore_noDetailsIsRendered() {
        withMountedRoot("page-intro-no-more") { root, element ->
            root.pageHeader(tr("Beiträge"), intro = PageIntroDef("t-view", "Text."))
            assertNull(element().querySelector("details"))
        }
    }

    @Test
    fun more_domOrderIsTextThenDetailsThenLinkThenCollapseButton() {
        withMountedRoot("page-intro-more-order") { root, element ->
            root.div { pageIntro("t-order", "Text.", helpTopic = "travel-expenses", more = "Mehr Text.") }
            val text = element().querySelector("section.page-intro .page-intro__text") as HTMLElement
            val details = element().querySelector("section.page-intro details") as HTMLElement
            val link = element().querySelector("section.page-intro a") as HTMLElement
            val button = element().querySelector("section.page-intro button.page-intro__collapse") as HTMLElement

            fun follows(
                first: HTMLElement,
                second: HTMLElement,
            ) = (first.compareDocumentPosition(second).toInt() and 4) != 0
            assertTrue(follows(text, details), "details follows the text")
            assertTrue(follows(details, link), "the help link follows details")
            assertTrue(follows(link, button), "the collapse button comes last")
            assertNull((details.querySelector("summary") as HTMLElement).getAttribute("tabindex"))
        }
    }

    @Test
    fun more_summaryTouchTargetIsAtLeast32px() {
        withMountedRoot("page-intro-more-target") { root, element ->
            root.pageHeader(tr("Beiträge"), intro = PageIntroDef("t-view", "Text.", more = "Mehr Text."))
            val box = (element().querySelector("details.page-intro__more > summary") as HTMLElement).getBoundingClientRect()
            assertTrue(box.height >= 31.5, "target ${box.width} x ${box.height}")
        }
    }

    @Test
    fun more_hasNoTransitionAndNoAnimation() {
        withMountedRoot("page-intro-more-motion") { root, element ->
            root.pageHeader(tr("Beiträge"), intro = PageIntroDef("t-view", "Text.", more = "Mehr Text."))
            val style = kotlinx.browser.window.getComputedStyle(element().querySelector("details.page-intro__more") as HTMLElement)
            assertEquals("none", style.getPropertyValue("animation-name"))
            assertTrue(style.getPropertyValue("transition-duration").split(",").all { it.trim() == "0s" })
        }
    }

    @Test
    fun collapse_removesTheWholeBody_includingTheDetails(): Promise<Unit> =
        formTest {
            withMountedRoot("page-intro-more-hide") { root, element ->
                root.pageHeader(tr("Beiträge"), intro = PageIntroDef("t-view", "Text.", more = "Mehr Text."))
                assertNotNull(element().querySelector("details"))
                (element().querySelector("button.page-intro__collapse") as HTMLElement).click()
                delay(120)
                assertNotNull(element().toggle())
                assertNull(element().querySelector("details"), "the details part goes with the body")
            }
        }
}
