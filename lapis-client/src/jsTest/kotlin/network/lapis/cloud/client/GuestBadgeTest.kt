package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * V0.8.4 Guest Badge: covers only the pure, DOM-independent text functions
 * ([guestBadgeAriaLabel], [guestBadgePopoverTitle], [guestBadgePopoverBody]) factored out of
 * [guestBadge] -- these run under the Karma+ChromeHeadless `testTask` already configured in
 * `lapis-client/build.gradle.kts`, same as [ValidationTest]. Explicitly NOT tested here, and why
 * (mirrors [ValidationTest]'s own precedent for this exact scope question): whether the popover
 * actually fires on hover/focus/tap, whether the badge visually replaces the role text in the
 * navbar, or whether `pointer-events: auto` genuinely restores interactivity under a `.disabled`
 * Bootstrap ancestor. No DOM/rendering test harness exists in this module (see [ValidationTest]
 * KDoc) -- building one just for this one badge would be disproportionate scope for this wave.
 * Manual QA substitute: log in as a seeded guest, hover/tab-focus/tap the badge, confirm the
 * popover text, and confirm a screen reader (or the DOM inspector's accessibility tree) reports
 * the `aria-label` without needing to trigger the popover.
 */
class GuestBadgeTest {
    @Test
    fun guestBadgeAriaLabel_includesHomeserverUrl() {
        assertEquals("Gast von https://home.example.org", guestBadgeAriaLabel("https://home.example.org"))
    }

    @Test
    fun guestBadgePopoverTitle_includesHomeserverUrl() {
        assertEquals("Gast von https://home.example.org", guestBadgePopoverTitle("https://home.example.org"))
    }

    @Test
    fun guestBadgePopoverBody_includesHomeserverUrl() {
        assertEquals(
            "Angemeldet über den OIDC-Heimserver https://home.example.org.",
            guestBadgePopoverBody("https://home.example.org"),
        )
    }
}
