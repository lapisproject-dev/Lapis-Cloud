package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * V1.9.339 -- the pure parts of the sidebar approval counters (Artikel, Beitragsvergünstigungen, Reisekosten-Freigaben,
 * Ehrenamtspauschalen-Freigaben): [approvalCountText] and [approvalAccessibleName]. The counter is a suffix span, never part of the
 * label; the DOM side is covered by [SidebarApprovalCountDomTest]. `TestI18nSetup`'s `I18nCatalogManager(emptyMap())` backs `gettext()`.
 */
class SidebarLabelsTest {
    // KVision's marker that `tr(key)` unconditionally prefixes -- `approvalAccessibleName` strips it via `plainMenuText`.
    private val kvI18nMarker = "###KvI18nS###"

    @Test
    fun approvalCountText_unknownEmptyOrNegative_isNull() {
        assertNull(approvalCountText(null))
        assertNull(approvalCountText(0))
        assertNull(approvalCountText(-1))
    }

    @Test
    fun approvalCountText_belowCap_isTheExactCount() {
        assertEquals("1", approvalCountText(1))
        assertEquals("199", approvalCountText(199))
    }

    @Test
    fun approvalCountText_atOrAboveCap_isTwoHundredPlus() {
        assertEquals("200+", approvalCountText(200))
        assertEquals("200+", approvalCountText(201))
        assertEquals("200+", approvalCountText(500))
        assertEquals("200+", approvalCountText(9999))
    }

    @Test
    fun approvalAccessibleName_namesTheLabelAndTheCount() {
        assertEquals("Artikel, 3 offen", approvalAccessibleName("${kvI18nMarker}Artikel", 3))
        assertEquals("Artikel, 200+ offen", approvalAccessibleName("${kvI18nMarker}Artikel", 200))
        assertEquals("Reisekosten-Freigaben, 1 offen", approvalAccessibleName("Reisekosten-Freigaben", 1))
    }

    @Test
    fun approvalAccessibleName_nothingToAnnounce_isNull() {
        assertNull(approvalAccessibleName("${kvI18nMarker}Artikel", 0))
        assertNull(approvalAccessibleName("${kvI18nMarker}Artikel", null))
    }
}
