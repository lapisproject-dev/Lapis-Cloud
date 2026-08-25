package network.lapis.cloud.client

import kotlinx.browser.document
import org.w3c.dom.HTMLScriptElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val BRAND_ELEMENT_ID = "lapis-brand"

private fun removeBrandElement() {
    document.getElementById(BRAND_ELEMENT_ID)?.remove()
}

/**
 * Inserts a fresh `#lapis-brand` `<script>` element with [json] as its text content, replacing
 * any previous one. `type = "application/json"` is load-bearing, not decorative -- a `<script>`
 * element defaults to `text/javascript`, and appending one WITHOUT an explicit non-executable
 * `type` makes the browser attempt to PARSE/EXECUTE [json] as JavaScript on `appendChild`, which
 * throws a `SyntaxError` for anything that is not also valid JS (crashing this whole Karma test
 * run, found live via the malformed-JSON test case below before this fix). Mirrors the real
 * `index.html`'s own `<script type="application/json" id="lapis-brand">` markup exactly.
 */
private fun setBrandElement(json: String) {
    removeBrandElement()
    val script = document.createElement("script") as HTMLScriptElement
    script.type = "application/json"
    script.id = BRAND_ELEMENT_ID
    script.textContent = json
    document.head?.appendChild(script)
}

/**
 * Exercises [Branding] against a real DOM -- Karma+ChromeHeadless runs this module's `jsTest` in
 * an actual browser (same environment `GuestBadgeTest`/`TestI18nSetup` already rely on), so
 * `kotlinx.browser.document` genuinely works here, unlike a JVM unit test. [Branding.title]/
 * [Branding.logoUrl] are computed on every read (not `by lazy`, see that object's own KDoc) --
 * exactly what makes it possible to mutate the DOM between test cases and observe a fresh result
 * each time, rather than a value cached from whichever test happened to run first.
 */
class BrandingTest {
    @AfterTest
    fun cleanup() {
        removeBrandElement()
    }

    @Test
    fun noElementInDom_fallsBackToDefaults() {
        removeBrandElement()
        assertEquals(Branding.DEFAULT_TITLE, Branding.title)
        assertNull(Branding.logoUrl)
    }

    @Test
    fun malformedJson_fallsBackToDefaults_neverThrows() {
        setBrandElement("{this is not valid json")
        assertEquals(Branding.DEFAULT_TITLE, Branding.title)
        assertNull(Branding.logoUrl)
    }

    @Test
    fun emptyElementContent_fallsBackToDefaults() {
        setBrandElement("")
        assertEquals(Branding.DEFAULT_TITLE, Branding.title)
        assertNull(Branding.logoUrl)
    }

    @Test
    fun validPayloadWithTitleAndLogo_readsBothCorrectly() {
        setBrandElement("""{"title":"Partei der Vernunft","logoUrl":"/api/branding/logo"}""")
        assertEquals("Partei der Vernunft", Branding.title)
        assertEquals("/api/branding/logo", Branding.logoUrl)
    }

    @Test
    fun payloadWithNullLogoUrl_logoUrlIsNull() {
        setBrandElement("""{"title":"ELB","logoUrl":null}""")
        assertEquals("ELB", Branding.title)
        assertNull(Branding.logoUrl)
    }

    @Test
    fun payloadWithBlankTitle_fallsBackToDefaultTitle() {
        setBrandElement("""{"title":"","logoUrl":null}""")
        assertEquals(Branding.DEFAULT_TITLE, Branding.title)
    }

    @Test
    fun payloadMissingTitleKeyEntirely_fallsBackToDefaultTitle() {
        setBrandElement("""{"logoUrl":"/api/branding/logo"}""")
        assertEquals(Branding.DEFAULT_TITLE, Branding.title)
        assertEquals("/api/branding/logo", Branding.logoUrl)
    }

    @Test
    fun reReadsOnEveryAccess_reflectsDomMutationBetweenReads() {
        setBrandElement("""{"title":"First","logoUrl":null}""")
        assertEquals("First", Branding.title)

        setBrandElement("""{"title":"Second","logoUrl":null}""")
        assertEquals("Second", Branding.title)
    }
}
