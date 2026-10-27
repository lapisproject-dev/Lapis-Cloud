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
    fun pollDetailRoute_activatesThePollsLink_butNotAnotherLink() {
        // V1.9.31: /polls/<id> keeps "Umfragen" highlighted; /polls does not light up an unrelated "/poll" prefix link.
        assertTrue(NavRouteMatch.isActive("/polls/4711", Routes.POLLS))
        assertTrue(NavRouteMatch.isActive(Routes.POLLS, Routes.POLLS))
        assertFalse(NavRouteMatch.isActive("/polls", "/poll"))
        assertFalse(NavRouteMatch.isActive("/consensus/4711", Routes.POLLS))
    }

    @Test
    fun committeeAndMeetingDetailRoutes_keepTheirListLinkActive() {
        // V1.9.192: /committees/<id> and /meetings/<id> highlight "Gremien" / "Sitzungen"; the route patterns do too.
        assertTrue(NavRouteMatch.isActive("/committees/c-1", Routes.COMMITTEES))
        assertTrue(NavRouteMatch.isActive(Routes.COMMITTEE_DETAIL, Routes.COMMITTEES))
        assertTrue(NavRouteMatch.isActive("/meetings/m-1", Routes.MEETINGS))
        assertTrue(NavRouteMatch.isActive(Routes.MEETING_DETAIL, Routes.MEETINGS))
        assertFalse(NavRouteMatch.isActive("/meetings/m-1", Routes.COMMITTEES))
        // V1.9.193: /motions/<id> highlights "Anträge".
        assertTrue(NavRouteMatch.isActive("/motions/a-1", Routes.MOTIONS))
        assertTrue(NavRouteMatch.isActive(Routes.MOTION_DETAIL, Routes.MOTIONS))
    }

    @Test
    fun eventDetailRoute_keepsTheEventsLinkActive_andIsNotTheImportOrTheCheckIn() {
        // V1.9.204: /events/<id> highlights "Veranstaltungen"; the literal /events/import is a route of its own and does the same, and the
        // check-in group ("/event-checkin") is not a descendant of "/events".
        assertTrue(NavRouteMatch.isActive("/events/e-1", Routes.EVENT_MANAGEMENT))
        assertTrue(NavRouteMatch.isActive(Routes.EVENT_DETAIL, Routes.EVENT_MANAGEMENT))
        assertTrue(NavRouteMatch.isActive(Routes.EVENT_IMPORT, Routes.EVENT_MANAGEMENT))
        // V1.9.226: the LupaBB sync is a sub-path of the member administration
        assertTrue(NavRouteMatch.isActive(Routes.MEMBER_LUPABB_SYNC, Routes.MEMBERS))
        assertFalse(NavRouteMatch.isActive("/event-checkin", Routes.EVENT_MANAGEMENT))
        assertFalse(NavRouteMatch.isActive("/events/e-1", Routes.EVENT_CHECKIN))
        // the import route is registered before the id route, and both are distinct literals
        assertTrue(Routes.EVENT_IMPORT != Routes.EVENT_DETAIL)
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

    @Test
    fun memberFinder_doesNotMatchNeighbours() {
        assertTrue(NavRouteMatch.isActive(Routes.MEMBER_FINDER, Routes.MEMBER_FINDER))
        assertFalse(NavRouteMatch.isActive("/member-finders", Routes.MEMBER_FINDER))
        assertFalse(NavRouteMatch.isActive("/members", Routes.MEMBER_FINDER))
        assertFalse(NavRouteMatch.isActive(Routes.MEMBER_FINDER, "/members"))
    }
}
