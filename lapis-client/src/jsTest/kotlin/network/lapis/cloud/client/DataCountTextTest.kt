package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.4.26 (W2): the one counter text of a data table, and the "nothing matched in the loaded
 * subset" sentence. Both are pure, so they are tested here rather than through a widget.
 *
 * The assertions deliberately check the SHAPE (which numbers appear, whether the caveat sentence is
 * there), not the German wording: `gettext` resolves through the catalog, and pinning a sentence here
 * would turn every translation fix into a test failure. What must not drift is the decision table --
 * which of the three forms a given combination of arguments produces.
 */
class DataCountTextTest {
    @Test
    fun filtered_countsShownAgainstLoaded() {
        val text = dataCountText(shown = 3, loaded = 40, filtered = true)
        assertTrue(text.contains("3"), text)
        assertTrue(text.contains("40"), text)
    }

    @Test
    fun filtered_withMoreOnTheServer_addsTheCaveatThatFiltersOnlyReachLoadedRows() {
        val withMore = dataCountText(shown = 0, loaded = 40, hasMore = true, filtered = true)
        val withoutMore = dataCountText(shown = 0, loaded = 40, hasMore = false, filtered = true)
        // The caveat is the whole point of the `hasMore` flag: "0 of 40" alone reads like "does not exist".
        assertTrue(withMore.length > withoutMore.length, withMore)
        assertTrue(withMore.startsWith(withoutMore), withMore)
        assertTrue(withMore.contains(" · "), withMore)
    }

    @Test
    fun unfiltered_withAKnownTotal_countsLoadedAgainstTotal() {
        val text = dataCountText(shown = 12, loaded = 12, total = 40)
        assertTrue(text.contains("12"), text)
        assertTrue(text.contains("40"), text)
    }

    @Test
    fun unfiltered_withAKnownTotal_ignoresHasMore() {
        // With a total on screen the reader can see for themselves that more exist -- no extra sentence.
        assertEquals(
            dataCountText(shown = 12, loaded = 12, total = 40, hasMore = false),
            dataCountText(shown = 12, loaded = 12, total = 40, hasMore = true),
        )
    }

    @Test
    fun unfiltered_withoutATotal_neverClaimsATotalItDoesNotKnow() {
        // A cursor chronology knows only what it has loaded. "12 of 12" would be a claim about a total,
        // so the number must appear exactly once.
        val text = dataCountText(shown = 12, loaded = 12, total = null, hasMore = false)
        assertEquals(1, Regex("""12""").findAll(text).count(), text)
    }

    @Test
    fun unfiltered_withoutATotal_andMoreOnTheServer_saysSo() {
        val withMore = dataCountText(shown = 12, loaded = 12, total = null, hasMore = true)
        val withoutMore = dataCountText(shown = 12, loaded = 12, total = null, hasMore = false)
        assertTrue(withMore.length > withoutMore.length, withMore)
        assertTrue(withMore.startsWith(withoutMore), withMore)
    }

    @Test
    fun filtered_takesPrecedenceOverAKnownTotal() {
        // A filtered view must count what is SHOWN against what is LOADED; the server total would hide
        // that the filter only reaches the loaded rows.
        val text = dataCountText(shown = 2, loaded = 12, total = 40, filtered = true)
        assertTrue(text.contains("2"), text)
        assertTrue(text.contains("12"), text)
        assertFalse(text.contains("40"), text)
    }

    @Test
    fun noMatchText_quotesTheTerm() {
        val text = loadedSubsetNoMatchText(term = "Schmidt", hasMore = false)
        assertTrue(text.contains("Schmidt"), text)
    }

    @Test
    fun noMatchText_withMoreOnTheServer_offersTheWayOut() {
        val withMore = loadedSubsetNoMatchText(term = "Schmidt", hasMore = true)
        val withoutMore = loadedSubsetNoMatchText(term = "Schmidt", hasMore = false)
        assertTrue(withMore.length > withoutMore.length, withMore)
        assertTrue(withMore.startsWith(withoutMore), withMore)
    }
}
