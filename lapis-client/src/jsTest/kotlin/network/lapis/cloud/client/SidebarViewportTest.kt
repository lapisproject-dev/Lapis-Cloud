package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers [shouldMountSidebarEagerly]'s pure, DOM-free breakpoint decision -- same DOM-free
 * unit-test posture as [SidebarGroupStorageTest]/[NavRouteMatchTest] (no rendering harness exists
 * in this module, see those tests' own KDoc). Does NOT (cannot, without such a harness) cover
 * whether `App.kt`'s `refreshShell` actually calls `sidebar.show()` at the right time, or whether
 * the sidebar element is genuinely present in the DOM afterward -- see Review-Fund 2026-09-08
 * (Finding 2, KRITISCH) and [shouldMountSidebarEagerly]'s own KDoc for that reasoning trail.
 * Manual QA substitute: load the app at a >=992px window width and confirm the sidebar renders
 * without any click; narrow the window below that and confirm it's gone until the hamburger button
 * is tapped.
 */
class SidebarViewportTest {
    @Test
    fun narrowerThanBreakpoint_doesNotMountEagerly() {
        assertFalse(shouldMountSidebarEagerly(991))
    }

    @Test
    fun exactlyAtBreakpoint_mountsEagerly() {
        assertTrue(shouldMountSidebarEagerly(SIDEBAR_DESKTOP_BREAKPOINT_PX))
    }

    @Test
    fun widerThanBreakpoint_mountsEagerly() {
        assertTrue(shouldMountSidebarEagerly(1920))
    }

    @Test
    fun typicalMobileWidth_doesNotMountEagerly() {
        assertFalse(shouldMountSidebarEagerly(375))
    }
}
