package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.ConferenceBackgroundRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review-Befund "Testabdeckung": die drei reinen, DOM-freien Helfer aus `ConferenceBackgroundUploads.kt` --
 * [conferenceBackgroundUploadTargetSize], [conferenceBackgroundUploadTooSmall] und
 * [conferenceBackgroundUploadErrorText] -- waren bisher komplett ungetestet (kein einziger Treffer im
 * jsTest-Verzeichnis). Kamera-/Canvas-/`createImageBitmap`-Pfade bleiben DOM-gebunden und damit unter
 * Karma nur ueber `ConferenceBackgroundSectionDomTest` indirekt abgedeckt.
 */
class ConferenceBackgroundUploadsTest {
    // --- conferenceBackgroundUploadTargetSize ----------------------------------------------------

    @Test
    fun targetSize_belowTheLimitOnBothEdges_isUnchanged() {
        val (width, height) = conferenceBackgroundUploadTargetSize(width = 800, height = 600)
        assertEquals(800 to 600, width to height)
    }

    @Test
    fun targetSize_exactlyAtTheLimit_isUnchanged() {
        val maxEdge = ConferenceBackgroundRules.MAX_OUTPUT_LONG_EDGE_PX
        val (width, height) = conferenceBackgroundUploadTargetSize(width = maxEdge, height = maxEdge / 2)
        assertEquals(maxEdge to maxEdge / 2, width to height)
    }

    @Test
    fun targetSize_wideImageAboveTheLimit_scalesTheLongEdgeDownAndKeepsAspectRatio() {
        val maxEdge = ConferenceBackgroundRules.MAX_OUTPUT_LONG_EDGE_PX
        // 2:1 Seitenverhaeltnis, doppelt so breit wie die Grenze.
        val (width, height) = conferenceBackgroundUploadTargetSize(width = maxEdge * 2, height = maxEdge)
        assertEquals(maxEdge, width, "the long edge is capped exactly at the limit")
        assertEquals(maxEdge / 2, height, "the aspect ratio survives the scale-down")
    }

    @Test
    fun targetSize_tallImageAboveTheLimit_scalesTheLongEdgeDown() {
        val maxEdge = ConferenceBackgroundRules.MAX_OUTPUT_LONG_EDGE_PX
        val (width, height) = conferenceBackgroundUploadTargetSize(width = maxEdge, height = maxEdge * 3)
        assertEquals(maxEdge / 3, width)
        assertEquals(maxEdge, height, "the long edge (height this time) is the one capped")
    }

    @Test
    fun targetSize_neverScalesUp() {
        val (width, height) = conferenceBackgroundUploadTargetSize(width = 10, height = 10)
        assertEquals(10 to 10, width to height)
    }

    @Test
    fun targetSize_extremeAspectRatio_neverRoundsAnEdgeToZero() {
        val maxEdge = ConferenceBackgroundRules.MAX_OUTPUT_LONG_EDGE_PX
        val (width, height) = conferenceBackgroundUploadTargetSize(width = maxEdge * 1000, height = 1)
        assertTrue(width > 0)
        assertTrue(height >= 1, "coerceAtLeast(1) -- a scaled-down edge is never 0")
    }

    // --- conferenceBackgroundUploadTooSmall ------------------------------------------------------

    @Test
    fun tooSmall_bothEdgesAtOrAboveTheMinimum_isFalse() {
        val min = ConferenceBackgroundRules.MIN_SIDE_PX
        assertFalse(conferenceBackgroundUploadTooSmall(targetWidth = min, targetHeight = min))
        assertFalse(conferenceBackgroundUploadTooSmall(targetWidth = min + 500, targetHeight = min))
    }

    @Test
    fun tooSmall_shorterEdgeBelowTheMinimum_isTrue() {
        val min = ConferenceBackgroundRules.MIN_SIDE_PX
        assertTrue(conferenceBackgroundUploadTooSmall(targetWidth = min - 1, targetHeight = min + 500))
        assertTrue(conferenceBackgroundUploadTooSmall(targetWidth = min + 500, targetHeight = min - 1))
    }

    // --- conferenceBackgroundUploadErrorText -----------------------------------------------------

    @Test
    fun errorText_everyKnownStatus_mapsToItsOwnDistinctSentence() {
        val statuses = listOf(413, 415, 422, 409, 429)
        val texts = statuses.map { conferenceBackgroundUploadErrorText(it) }
        assertEquals(statuses.size, texts.toSet().size, "each known status has its own fixed sentence, never shared with another")
        texts.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun errorText_anyUnknownStatus_fallsBackToTheGenericSentence() {
        val generic = conferenceBackgroundUploadErrorText(500)
        assertEquals(generic, conferenceBackgroundUploadErrorText(0))
        assertEquals(generic, conferenceBackgroundUploadErrorText(999))
        assertEquals(generic, conferenceBackgroundUploadErrorText(-1))
    }
}
