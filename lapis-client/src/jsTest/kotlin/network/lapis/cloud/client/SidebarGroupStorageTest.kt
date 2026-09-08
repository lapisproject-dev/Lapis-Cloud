package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [SidebarGroupStorage]'s pure, DOM-free `parse`/`serialize` pair and [sidebarGroupForRoute]
 * -- same DOM-free unit-test posture as [NavVisibilityTest]/[NavRouteMatchTest] (no rendering
 * harness exists in this module). `load`/`save` (the only `localStorage`-touching, impure parts of
 * [SidebarGroupStorage]) are deliberately NOT covered here for the same reason `NavHighlight`
 * itself has no unit test of its own -- see [NavRouteMatchTest]'s own KDoc.
 */
class SidebarGroupStorageTest {
    @Test
    fun parse_null_isEmpty() {
        assertEquals(emptySet(), SidebarGroupStorage.parse(null))
    }

    @Test
    fun parse_blank_isEmpty() {
        assertEquals(emptySet(), SidebarGroupStorage.parse(""))
    }

    @Test
    fun parse_knownTokens_resolvesToTheirGroups() {
        assertEquals(
            setOf(SidebarGroupId.MEMBERSHIP, SidebarGroupId.FINANCE),
            SidebarGroupStorage.parse("membership,finance"),
        )
    }

    @Test
    fun parse_unknownToken_isIgnored() {
        assertEquals(
            setOf(SidebarGroupId.MEMBERSHIP, SidebarGroupId.FINANCE),
            SidebarGroupStorage.parse("membership,unknown-token,finance"),
        )
    }

    @Test
    fun parse_onlyUnknownTokens_isEmpty() {
        assertEquals(emptySet(), SidebarGroupStorage.parse("nope,also-not-a-group"))
    }

    @Test
    fun serialize_ordersByEnumDeclarationOrder_notInputSetIterationOrder() {
        // Built with FINANCE inserted before MEMBERSHIP -- a `LinkedHashSet`-backed `setOf(...)`
        // would iterate FINANCE first if `serialize` naively walked the input set. `serialize`
        // must instead walk `SidebarGroupId.entries` (MEMBERSHIP is declared before FINANCE), so
        // the output stays deterministic regardless of how the caller built the set.
        val ids = setOf(SidebarGroupId.FINANCE, SidebarGroupId.MEMBERSHIP)
        assertEquals("membership,finance", SidebarGroupStorage.serialize(ids))
    }

    @Test
    fun serialize_empty_isEmptyString() {
        assertEquals("", SidebarGroupStorage.serialize(emptySet()))
    }

    @Test
    fun roundTrip_parseOfSerialize_recoversTheOriginalSet() {
        val cases =
            listOf(
                emptySet(),
                setOf(SidebarGroupId.SYSTEM),
                setOf(SidebarGroupId.MEMBERSHIP, SidebarGroupId.SELF_GOVERNANCE, SidebarGroupId.ADMINISTRATION),
                SidebarGroupId.entries.toSet(),
            )
        cases.forEach { ids ->
            assertEquals(ids, SidebarGroupStorage.parse(SidebarGroupStorage.serialize(ids)))
        }
    }

    @Test
    fun sidebarGroupForRoute_financeRoute_resolvesToFinance() {
        assertEquals(SidebarGroupId.FINANCE, sidebarGroupForRoute("/ledger"))
        assertEquals(SidebarGroupId.FINANCE, sidebarGroupForRoute("/audit-log"))
    }

    @Test
    fun sidebarGroupForRoute_ungroupedTopLevelRoute_isNull() {
        assertNull(sidebarGroupForRoute("/dashboard"))
        assertNull(sidebarGroupForRoute("/conference"))
    }

    @Test
    fun sidebarGroupForRoute_null_isNull() {
        assertNull(sidebarGroupForRoute(null))
    }

    @Test
    fun sidebarGroupForRoute_parameterizedDescendantRoute_resolvesToItsGroupLinksGroup() {
        // Same slash-suffixed prefix rule as `NavRouteMatch.isActive` (reused, not reimplemented)
        // -- a route one level deeper than the group's own link route still resolves to that
        // link's group, mirroring `NavRouteMatchTest.parameterizedDescendantRoute_activatesItsGroupLink`.
        assertEquals(SidebarGroupId.ECONOMY, sidebarGroupForRoute("/social-network/post/abc123"))
    }
}
