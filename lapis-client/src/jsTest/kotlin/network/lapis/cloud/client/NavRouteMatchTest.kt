package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [NavRouteMatch]'s pure, DOM-free active-route predicate -- same DOM-free unit-test
 * posture as [NavVisibilityTest] (no rendering harness exists in this module), so [NavHighlight]
 * itself (the DOM-touching consumer) deliberately has no unit test of its own.
 */
class NavRouteMatchTest {
    @Test
    fun exactMatch_isActive() {
        assertTrue(NavRouteMatch.isActive("/dashboard", "/dashboard"))
    }

    @Test
    fun parameterizedDescendantRoute_activatesItsGroupLink() {
        assertTrue(NavRouteMatch.isActive("/social-network/post/abc123", "/social-network"))
    }

    @Test
    fun groupRoute_doesNotActivateItsOwnDescendantLink() {
        assertFalse(NavRouteMatch.isActive("/social-network", "/social-network/post/:id"))
    }

    @Test
    fun prefixCollision_doesNotFalseMatch() {
        assertFalse(NavRouteMatch.isActive("/cost-centers", "/cost"))
    }

    @Test
    fun unrelatedRoutes_neverMatch() {
        assertFalse(NavRouteMatch.isActive("/dashboard", "/dsgvo-rights"))
    }

    @Test
    fun nullCurrentRoute_isNeverActive() {
        assertFalse(NavRouteMatch.isActive(null, "/dashboard"))
    }
}
