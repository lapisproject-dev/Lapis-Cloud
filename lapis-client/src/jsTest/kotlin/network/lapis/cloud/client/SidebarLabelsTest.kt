package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Review-Fund 2026-09-12 (Welle V1.4.10.1 "Beitragsvergünstigungen: Bedienoberfläche"): covers
 * [reliefSidebarLabel], the one pure, DOM-independent function `Sidebar.kt` gained this wave --
 * its own KDoc already pointed at a "SidebarLabelsTest" that did not exist yet. Same scope
 * posture as [ContributionReliefLabelsTest] (no rendering harness needed, `TestI18nSetup`'s
 * `I18nCatalogManager(emptyMap())` backs `tr()`/`gettext()`).
 *
 * `tr(key)` and `gettext(key, *args)` behave differently here (live-verified, NOT symmetric the
 * way [ContributionReliefLabelsTest]'s all-`gettext()` functions are): `gettext` resolves through
 * [I18nCatalogManager] immediately and substitutes `%N` placeholders, but `tr` unconditionally
 * prefixes its result with KVision's own `"###KvI18nS###"` dynamic-retranslation marker regardless
 * of which manager is installed (same marker [SidebarStructureTest] already strips off `Button.text`
 * and `ConferenceScreen.kt`'s `resolvedA11yText` strips off ARIA text -- see either KDoc). That is
 * correct here, not a bug: `reliefSidebarLabel(null)`'s return value is only ever used as the
 * INITIAL label argument to `sidebarLink(...)` (i.e. a widget constructor param, never a bare
 * rendered string), exactly like every other flat/group-header label in this file (`tr("Dashboard")`
 * etc.) -- it needs the marker so KVision can re-translate the live widget on a language switch.
 */
class SidebarLabelsTest {
    // KVision's internal marker `tr(key)` unconditionally prefixes its result with, regardless of
    // the installed `I18nManager` -- see this class's own KDoc and [SidebarStructureTest].
    private val kvI18nMarker = "###KvI18nS###"

    @Test
    fun reliefSidebarLabel_null_isThePlainLabelWithNoBadge() {
        assertEquals("${kvI18nMarker}Beitragsvergünstigungen", reliefSidebarLabel(null))
    }

    @Test
    fun reliefSidebarLabel_zero_isThePlainLabelWithNoBadge() {
        // Distinct from `null` at the call site (`AppScope.launch`'s own `openCount != null &&
        // openCount > 0` guard never overwrites the label for a resolved 0), but `reliefSidebarLabel`
        // itself is defensive and folds both into the same branch.
        assertEquals("${kvI18nMarker}Beitragsvergünstigungen", reliefSidebarLabel(0))
    }

    @Test
    fun reliefSidebarLabel_belowCap_showsTheExactCount() {
        assertEquals("Beitragsvergünstigungen (1)", reliefSidebarLabel(1))
        assertEquals("Beitragsvergünstigungen (199)", reliefSidebarLabel(199))
    }

    @Test
    fun reliefSidebarLabel_atCap_showsTwoHundredPlusNotTheExactCount() {
        // `listReliefRequests`' own `MAX_LIST_RESULTS = 200` page-size cap -- 200 is the first value
        // this sidebar counter can never have actually verified as exact (a >=200 comparison, not
        // >200 -- off-by-one here would silently claim an exact count of "200" the capped RPC call
        // never actually confirmed).
        assertEquals("Beitragsvergünstigungen (200+)", reliefSidebarLabel(200))
    }

    @Test
    fun reliefSidebarLabel_aboveCap_stillShowsTwoHundredPlus() {
        assertEquals("Beitragsvergünstigungen (200+)", reliefSidebarLabel(201))
        assertEquals("Beitragsvergünstigungen (200+)", reliefSidebarLabel(9999))
    }
}
