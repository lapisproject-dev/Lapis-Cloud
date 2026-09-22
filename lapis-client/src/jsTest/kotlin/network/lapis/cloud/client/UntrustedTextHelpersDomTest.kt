package network.lapis.cloud.client

import io.kvision.html.div
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Security audit W6b round 4: through a real mounted [io.kvision.panel.Root] (not just a unit test of
 * [sanitizeUntrustedI18nText] itself), proves that every one of the five `untrusted*` widget-content helpers in
 * `UntrustedText.kt` sanitizes a forged `KV_I18N_MARKER` + `MONEY_SENTINEL` payload before it ever reaches the DOM
 * -- so a screen that hands an untrusted DTO field to one of these helpers cannot render a fabricated amount, even
 * without a per-screen `sanitizeUntrustedI18nText(...)` wrap. Also proves ordinary text (umlauts, `%1`, `<`, `&`)
 * survives untouched -- the helpers must not damage legitimate content while stripping the control sequence.
 *
 * Round 6 review (minor finding, missing test coverage): the sixth, assignment-shape helper, [untrustedContent],
 * had no DOM test of its own -- only [network.lapis.cloud.server.clientversion.ClientUntrustedWidgetTextTripwireTest]
 * exercised it, and only as a string inside its detector/ledger regexes (it tests that the *call* is present in
 * source, never what the call actually does at runtime). The `untrustedContent_*` tests below close that gap,
 * mirroring the construction-time helpers above: a forged-marker case (must be stripped), an ordinary-text case
 * (must survive untouched), and a `null` case (must clear the widget's content) specific to this helper's signature.
 */
class UntrustedTextHelpersDomTest {
    private val forgedPayload = KV_I18N_MARKER + MONEY_SENTINEL + MONEY_KIND_LTR + "9999"
    private val ordinaryText = "Ortsverband Braunschweig, 50% Quorum <streng> & vertraulich"

    @Test
    fun untrustedDiv_stripsAForgedMarkerPayload_andLeavesOrdinaryTextIntact() {
        withMountedRoot("untrusted-div-test") { root, element ->
            root.untrustedDiv(forgedPayload, className = "flex-grow-1")
            val text = element().textContent.orEmpty()
            assertFalse(text.contains(KV_I18N_MARKER), "marker must not survive: $text")
            assertFalse(text.contains(MONEY_SENTINEL), "sentinel must not survive: $text")
        }
        withMountedRoot("untrusted-div-ordinary-test") { root, element ->
            root.untrustedDiv(ordinaryText)
            assertEquals(ordinaryText, element().textContent)
        }
    }

    @Test
    fun untrustedSpan_stripsAForgedMarkerPayload_andLeavesOrdinaryTextIntact() {
        withMountedRoot("untrusted-span-test") { root, element ->
            root.untrustedSpan(forgedPayload, className = "fw-bold")
            val text = element().textContent.orEmpty()
            assertFalse(text.contains(KV_I18N_MARKER), "marker must not survive: $text")
            assertFalse(text.contains(MONEY_SENTINEL), "sentinel must not survive: $text")
        }
        withMountedRoot("untrusted-span-ordinary-test") { root, element ->
            root.untrustedSpan(ordinaryText)
            assertEquals(ordinaryText, element().textContent)
        }
    }

    @Test
    fun untrustedP_stripsAForgedMarkerPayload_andLeavesOrdinaryTextIntact() {
        withMountedRoot("untrusted-p-test") { root, element ->
            root.untrustedP(forgedPayload, className = "mb-0")
            val text = element().textContent.orEmpty()
            assertFalse(text.contains(KV_I18N_MARKER), "marker must not survive: $text")
            assertFalse(text.contains(MONEY_SENTINEL), "sentinel must not survive: $text")
        }
        withMountedRoot("untrusted-p-ordinary-test") { root, element ->
            root.untrustedP(ordinaryText)
            assertEquals(ordinaryText, element().textContent)
        }
    }

    @Test
    fun untrustedHeading_stripsAForgedMarkerPayload_andLeavesOrdinaryTextIntact() {
        withMountedRoot("untrusted-heading-test") { root, element ->
            root.untrustedHeading(forgedPayload, level = 2, className = "h5")
            val text = element().textContent.orEmpty()
            assertFalse(text.contains(KV_I18N_MARKER), "marker must not survive: $text")
            assertFalse(text.contains(MONEY_SENTINEL), "sentinel must not survive: $text")
            assertTrue(element().querySelector("h2") != null, "must render as an h2")
        }
        withMountedRoot("untrusted-heading-ordinary-test") { root, element ->
            root.untrustedHeading(ordinaryText, level = 2)
            assertEquals(ordinaryText, element().textContent)
        }
    }

    // Security audit W6b, round 5 (minor finding, test coverage): only `level = 2` was ever exercised -- the 3/4/5/6
    // branches and the R6 `else -> error(...)` guard (h1 is reserved to `PageHeader.kt`'s `pageHeader()`) had no
    // test, so a future refactor could silently break either without failing the suite.
    @Test
    fun untrustedHeading_rendersTheCorrectTagForEachSupportedLevel() {
        val levelToTag = mapOf(2 to "h2", 3 to "h3", 4 to "h4", 5 to "h5", 6 to "h6")
        levelToTag.forEach { (level, tag) ->
            withMountedRoot("untrusted-heading-level-$level-test") { root, element ->
                root.untrustedHeading(forgedPayload, level = level, className = "h5")
                val text = element().textContent.orEmpty()
                assertFalse(text.contains(KV_I18N_MARKER), "marker must not survive at level $level: $text")
                assertFalse(text.contains(MONEY_SENTINEL), "sentinel must not survive at level $level: $text")
                assertTrue(element().querySelector(tag) != null, "level $level must render as <$tag>")
            }
        }
    }

    @Test
    fun untrustedHeading_rejectsAnUnsupportedLevel_h1IsReservedToPageHeader() {
        withMountedRoot("untrusted-heading-invalid-level-test") { root, _ ->
            val exceptionForH1 =
                assertFailsWith<IllegalStateException> {
                    root.untrustedHeading(ordinaryText, level = 1)
                }
            assertTrue(
                exceptionForH1.message.orEmpty().contains("h1 belongs to PageHeader.kt"),
                "message must explain the h1 exception: ${exceptionForH1.message}",
            )
            assertFailsWith<IllegalStateException> {
                root.untrustedHeading(ordinaryText, level = 7)
            }
        }
    }

    @Test
    fun untrustedCardTitle_stripsAForgedMarkerPayload_andCarriesTheStandardHeaderClasses() {
        withMountedRoot("untrusted-card-title-test") { root, element ->
            root.untrustedCardTitle(forgedPayload)
            val text = element().textContent.orEmpty()
            assertFalse(text.contains(KV_I18N_MARKER), "marker must not survive: $text")
            assertFalse(text.contains(MONEY_SENTINEL), "sentinel must not survive: $text")
            val div = assertNotNull(element().querySelector("div"), "must render an inner div")
            assertTrue(div.className.contains("flex-grow-1"), "must carry flex-grow-1: ${div.className}")
            assertTrue(div.className.contains("fw-bold"), "must carry fw-bold: ${div.className}")
        }
        withMountedRoot("untrusted-card-title-ordinary-test") { root, element ->
            root.untrustedCardTitle(ordinaryText)
            assertEquals(ordinaryText, element().textContent)
        }
    }

    // Security audit W6b, round 7 (major finding 1): [io.kvision.html.Link] renders `label` through the exact same
    // `Widget.translate`/`I18n.trans` sink as `Tag.content` -- `untrustedLink` closes the gap the round-3/4 helpers
    // above left open for `link(...)`.
    @Test
    fun untrustedLink_stripsAForgedMarkerPayload_andLeavesOrdinaryTextIntact() {
        withMountedRoot("untrusted-link-test") { root, element ->
            root.untrustedLink(forgedPayload, url = "javascript:void(0)")
            val text = element().textContent.orEmpty()
            assertFalse(text.contains(KV_I18N_MARKER), "marker must not survive: $text")
            assertFalse(text.contains(MONEY_SENTINEL), "sentinel must not survive: $text")
        }
        withMountedRoot("untrusted-link-ordinary-test") { root, element ->
            root.untrustedLink(ordinaryText, url = "javascript:void(0)")
            assertEquals(ordinaryText, element().textContent)
        }
    }

    @Test
    fun untrustedLink_passesTheUrlThroughUnchanged() {
        withMountedRoot("untrusted-link-url-test") { root, element ->
            root.untrustedLink(ordinaryText, url = "https://example.invalid/receipt/42")
            val anchor = assertNotNull(element().querySelector("a"), "must render an <a>")
            assertTrue(anchor.getAttribute("href").orEmpty().contains("example.invalid/receipt/42"))
        }
    }

    @Test
    fun untrustedContent_stripsAForgedMarkerPayload_andLeavesOrdinaryTextIntact() {
        withMountedRoot("untrusted-content-test") { root, element ->
            val widget = root.div("placeholder")
            untrustedContent(widget, forgedPayload)
            val text = element().textContent.orEmpty()
            assertFalse(text.contains(KV_I18N_MARKER), "marker must not survive: $text")
            assertFalse(text.contains(MONEY_SENTINEL), "sentinel must not survive: $text")
        }
        withMountedRoot("untrusted-content-ordinary-test") { root, element ->
            val widget = root.div("placeholder")
            untrustedContent(widget, ordinaryText)
            assertEquals(ordinaryText, element().textContent)
        }
    }

    @Test
    fun untrustedContent_withNullText_clearsTheWidgetsContent() {
        withMountedRoot("untrusted-content-null-test") { root, element ->
            val widget = root.div(ordinaryText)
            assertEquals(ordinaryText, element().textContent)
            untrustedContent(widget, null)
            assertEquals("", element().textContent.orEmpty())
        }
    }
}
