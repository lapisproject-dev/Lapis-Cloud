package network.lapis.cloud.client

import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.tr
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Welle V1.4.31 (W5): the page header in a REAL mounted root (Karma runs in Chrome, so insert hooks and focus are real).
 * Pins what the header promises: one `h1` with `id`/`tabindex` from the first render, banner above title above subtitle,
 * a marker-free `document.title` with the operator's brand, a live region that is only WRITTEN (never re-created), focus
 * after a route change but NOT after a re-render, and a subtitle that is text, never markup.
 */
class PageHeaderDomTest {
    private var liveRegion: HTMLElement? = null

    @AfterTest
    fun cleanUp() {
        liveRegion?.remove()
        liveRegion = null
        PageFocus.consume()
    }

    private fun mountLiveRegion(): HTMLElement {
        val region = document.createElement("div") as HTMLElement
        region.id = LIVE_REGION_ID
        document.body!!.appendChild(region)
        liveRegion = region
        return region
    }

    @Test
    fun header_hasExactlyOneH1_withIdAndTabindex_bannerBeforeTitleBeforeSubtitle() {
        withMountedRoot("page-header-structure") { root, element ->
            root.pageHeader(
                tr("Mitglieder"),
                subtitle = "Erika Musterfrau",
                banners = { div("Warnband") },
                primaryAction = { button("Neu") },
            )
            val h1s = element().querySelectorAll("h1")
            assertEquals(1, h1s.length)
            val h1 = h1s.item(0) as HTMLElement
            assertEquals(PAGE_TITLE_ID, h1.id)
            assertEquals("-1", h1.getAttribute("tabindex"))
            assertEquals("Mitglieder", h1.textContent)
            assertTrue(h1.classList.contains("h4"), "the h1 is styled .h4 (R7)")
            val text = element().textContent.orEmpty()
            assertTrue(text.indexOf("Warnband") < text.indexOf("Mitglieder"), "banner above the title (R37)")
            assertTrue(text.indexOf("Mitglieder") < text.indexOf("Erika Musterfrau"), "subtitle below the title")
            assertNotNull(element().querySelector(".lapis-page-action button"), "the primary action sits in the title row (R36)")
        }
    }

    @Test
    fun documentTitle_isMarkerFree_andUsesTheOperatorBrand() {
        withMountedRoot("page-header-doc-title") { root, _ ->
            root.pageHeader(tr("Mitglieder"))
            assertEquals("Mitglieder – ${Branding.title}", document.title)
            assertFalse(document.title.contains(KV_I18N_MARKER))
        }
    }

    @Test
    fun documentTitle_isTranslated() {
        withTranslations(mapOf("Mitglieder" to "Members")) {
            withMountedRoot("page-header-doc-title-i18n") { root, element ->
                root.pageHeader(tr("Mitglieder"))
                assertEquals("Members – ${Branding.title}", document.title)
                assertEquals("Members", element().querySelector("h1")!!.textContent)
            }
        }
    }

    @Test
    fun liveRegion_isWritten_notRecreated() {
        val region = mountLiveRegion()
        withMountedRoot("page-header-live") { root, _ ->
            root.pageHeader(tr("Mitglieder"))
            assertEquals("Mitglieder", region.textContent)
            root.pageHeader(tr("Gremien"))
            assertEquals("Gremien", region.textContent)
            assertSame(region, document.getElementById(LIVE_REGION_ID), "the same element, only its text changed")
            assertEquals(1, document.querySelectorAll("#$LIVE_REGION_ID").length)
        }
    }

    @Test
    fun focus_movesToTheH1_afterARouteChangeRequest() {
        withMountedRoot("page-header-focus-route") { root, element ->
            PageFocus.request()
            root.pageHeader(tr("Mitglieder"))
            assertSame(element().querySelector("h1"), document.activeElement, "the h1 has the focus after a route change")
        }
    }

    @Test
    fun focus_staysWhereItWas_onAReRenderWithoutARouteChange() {
        withMountedRoot("page-header-focus-rerender") { root, element ->
            val other = root.button("Sprache")
            (other.getElement() as HTMLElement).focus()
            assertSame(other.getElement(), document.activeElement)
            // A language switch / refreshShell rebuilds the header WITHOUT a route request: no focus grab.
            root.pageHeader(tr("Mitglieder"))
            assertSame(other.getElement(), document.activeElement, "focus must stay on the control the person was using")
            assertNull(element().querySelector("h1")?.let { if (document.activeElement === it) it else null })
        }
    }

    @Test
    fun focusRequest_isConsumedOnce() {
        withMountedRoot("page-header-focus-once") { root, _ ->
            PageFocus.request()
            root.pageHeader(tr("Mitglieder"))
            assertFalse(PageFocus.consume(), "the first header consumed the request")
        }
    }

    @Test
    fun subtitle_isText_neverMarkup() {
        withMountedRoot("page-header-xss") { root, element ->
            val header = root.pageHeader(tr("Mitglieder"), subtitle = "")
            header.setSubtitle("<img src=x onerror=\"window.__xss=1\">")
            assertNull(element().querySelector("img"), "no element may be created from a data value")
            assertTrue(element().textContent.orEmpty().contains("<img src=x"))
        }
    }

    @Test
    fun setSubtitle_withoutSubtitleSlot_failsLoudly_insteadOfDroppingTheValue() {
        withMountedRoot("page-header-no-subtitle") { root, element ->
            val header = root.pageHeader(tr("Mitglieder"))
            val failure = runCatching { header.setSubtitle("ignored") }.exceptionOrNull()
            assertNotNull(failure, "a value with nowhere to go must not vanish silently")
            assertTrue(failure is IllegalStateException)
            assertFalse(element().textContent.orEmpty().contains("ignored"))
            header.setSubtitle(null) // clearing needs no slot
            assertFalse(header.hasSubtitleSlot)
        }
    }

    @Test
    fun documentTitle_namesTheSubtitle_soTwoTabsOfOneScreenAreTold_apart() {
        withMountedRoot("page-header-doc-subtitle") { root, _ ->
            val header = root.pageHeader(tr("Beitragshistorie"), subtitle = "")
            assertEquals("Beitragshistorie – ${Branding.title}", document.title)
            header.setSubtitle("Erika Musterfrau")
            assertEquals("Beitragshistorie – Erika Musterfrau – ${Branding.title}", document.title)
            header.setSubtitle(null)
            assertEquals("Beitragshistorie – ${Branding.title}", document.title)
        }
    }

    @Test
    fun documentTitle_followsALanguageSwitch() {
        withMountedRoot("page-header-doc-title-switch") { root, _ ->
            root.pageHeader(tr("Mitglieder"), subtitle = "")
            assertEquals("Mitglieder – ${Branding.title}", document.title)
            // the language changes AFTER the header was built (what `I18n.language = ...` does to a mounted screen)
            withTranslations(mapOf("Mitglieder" to "Members")) {
                assertEquals("Mitglieder – ${Branding.title}", document.title, "nothing rewrote the tab yet")
                PageTitle.apply()
                assertEquals("Members – ${Branding.title}", document.title)
                assertFalse(document.title.contains(KV_I18N_MARKER))
            }
        }
    }

    @Test
    fun subtitleAside_standsNextToTheSubtitle_inOneRow() {
        withMountedRoot("page-header-aside") { root, element ->
            val header = root.pageHeader(tr("Beitragshistorie"), subtitle = "Erika Musterfrau")
            header.subtitleAside { span("DSGVO-gelöscht") }
            val row = element().querySelector(".lapis-page-header > div.d-flex.align-items-center") as HTMLElement
            assertTrue(row.textContent.orEmpty().contains("Erika Musterfrau"))
            assertTrue(row.textContent.orEmpty().contains("DSGVO-gelöscht"), "the badge is in the SAME row as the name")
        }
    }

    // ---- focus: first route, modal, focused field -------------------------------------------------------------------------

    @Test
    fun theFirstRouteShown_takesNoFocus_soTheSkipLinkStaysTheFirstTabStop_theSecondDoes() {
        PageFocus.reset()
        try {
            withMountedRoot("page-header-first-route") { root, element ->
                PageFocus.onRouteShown() // the page load / boot redirect
                root.pageHeader(tr("Mitglieder"))
                assertNotSame(element().querySelector("h1"), document.activeElement, "no focus grab on the initial route")
                PageFocus.onRouteShown() // a genuine route change
                root.pageHeader(tr("Gremien"))
                val h1s = element().querySelectorAll("h1")
                assertSame(h1s.item(1), document.activeElement, "the second route moves the focus to its h1")
            }
        } finally {
            PageFocus.reset()
        }
    }

    @Test
    fun theSkipLink_isTheFirstFocusableThingOfTheDocumentOrder() {
        // the shell mounts the skip link before everything else (App.kt); this pins the DOM contract the Tab order relies on
        val holder = document.createElement("div") as HTMLElement
        holder.innerHTML =
            "<button class=\"lapis-skip-link\">Zum Inhalt springen</button><nav><a href=\"#/x\">x</a></nav><main tabindex=\"-1\"></main>"
        document.body!!.appendChild(holder)
        try {
            val focusable = holder.querySelectorAll("button, a[href], input, select, textarea, [tabindex]:not([tabindex='-1'])")
            assertTrue((focusable.item(0) as HTMLElement).classList.contains("lapis-skip-link"))
        } finally {
            holder.remove()
        }
    }

    @Test
    fun focus_isNotStolen_fromAFieldThePersonIsUsing() {
        withMountedRoot("page-header-focus-field") { root, _ ->
            val field = document.createElement("input") as HTMLElement
            document.body!!.appendChild(field)
            try {
                field.focus()
                PageFocus.request()
                root.pageHeader(tr("Mitglieder"))
                assertSame(field, document.activeElement, "a route change fired from inside a field must not pull the caret away")
            } finally {
                field.remove()
            }
        }
    }

    @Test
    fun focus_isNotStolen_fromAnOpenModal() {
        withMountedRoot("page-header-focus-modal") { root, _ ->
            val modal = document.createElement("div") as HTMLElement
            modal.className = "modal show"
            document.body!!.appendChild(modal)
            try {
                PageFocus.request()
                root.pageHeader(tr("Mitglieder"))
                assertNotSame(document.querySelector("h1"), document.activeElement, "the modal's focus trap owns the focus")
            } finally {
                modal.remove()
            }
        }
    }
}
