package network.lapis.cloud.client

import io.kvision.panel.vPanel
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    @Test
    fun friendBadgeTexts_nameNoHomeserver() {
        assertEquals("Freund", friendBadgeAriaLabel())
        assertEquals("Freund", friendBadgePopoverTitle())
        assertEquals("Hat ein Freundeskonto auf diesem Server, ist aber kein Mitglied.", friendBadgePopoverBody())
        assertFalse(friendBadgePopoverBody().contains("http"))
    }

    private fun session(
        status: MemberStatus,
        isGuest: Boolean = false,
    ) = SessionInfoDto(
        memberId = "m-1",
        displayName = "Dana Keller",
        role = AccountRole.MEMBER,
        expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        isGuest = isGuest,
        homeserverUrl = if (isGuest) "https://home.example.org" else null,
        status = status,
    )

    @Test
    fun isFriendVisitor_onlyForANonGuestFriend() {
        assertTrue(isFriendVisitor(session(MemberStatus.FRIEND)))
        assertFalse(isFriendVisitor(session(MemberStatus.ACTIVE)))
        assertFalse(isFriendVisitor(session(MemberStatus.GUEST, isGuest = true)))
        assertFalse(isFriendVisitor(session(MemberStatus.FRIEND, isGuest = true)), "a guest keeps the guest badge")
    }

    @Test
    fun guestBadge_usesTheHikerGlyph_withoutAnySvg(): Promise<Unit> =
        formTest {
            mountedForm("visitor-badge-guest") { root, element ->
                root.vPanel { guestBadge("https://home.example.org") }
                awaitUntil("the badge glyph") { element().querySelector("[role='img'] .fa-person-hiking") != null }
                val badge = element().allOf("[role='img']").single()
                assertEquals("Gast von https://home.example.org", badge.getAttribute("aria-label"))
                val glyph = assertNotNull(badge.querySelector(".fa-person-hiking") as? HTMLElement)
                assertEquals("true", glyph.getAttribute("aria-hidden"))
                assertTrue(badge.querySelector("svg") == null, "no inline SVG in the badge")
            }
        }

    @Test
    fun friendBadge_usesTheSameGlyph_andItsOwnLabel(): Promise<Unit> =
        formTest {
            mountedForm("visitor-badge-friend") { root, element ->
                root.vPanel { friendBadge() }
                awaitUntil("the badge glyph") { element().querySelector("[role='img'] .fa-person-hiking") != null }
                val badge = element().allOf("[role='img']").single()
                assertEquals("Freund", badge.getAttribute("aria-label"))
                assertNotNull(badge.querySelector(".fa-person-hiking"))
                assertTrue(badge.querySelector("svg") == null)
                assertEquals("0", badge.getAttribute("tabindex"))
            }
        }
}
