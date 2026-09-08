package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun testSession(memberId: String = "member-1"): SessionInfoDto =
    SessionInfoDto(
        memberId = memberId,
        displayName = "Testperson",
        role = AccountRole.MEMBER,
        expiresAt = LocalDateTime(2026, 9, 8, 12, 0),
    )

/**
 * Review-Fund 2026-09-08 Runde 4 (Finding 1, KRITISCH; Finding 3, HOCH -- Testabdeckung) --
 * [AppState.setSession]'s no-op guard, DOM-free and directly unit-testable (unlike `App.kt`'s
 * `refreshShell()`, which is a local function with no exposed seam and genuinely needs a KVision
 * DOM-rendering harness this module does not have -- same established precedent
 * [SidebarViewportTest]/`LapisAttributionTest`/`GuestBadgeTest` already document; see those
 * classes' own KDoc). This is the pure half of the fix: [AppState] is a plain Kotlin `object`, so
 * asserting exactly when [AppState.onSessionChange] fires needs nothing beyond a listener counter.
 *
 * The regression this guards: `App.kt`'s boot sequence calls [AppState.setSession] with the
 * boot-time session probe's result right after its own initial, synchronous shell render already
 * reflects the anonymous (`null`) default -- for the ordinary anonymous visitor, that is a
 * `null` -> `null` call carrying no real information. Before this fix, [AppState.setSession] fired
 * [AppState.onSessionChange] regardless, which (wired to `App.kt`'s `refreshShell`) forced a
 * second, redundant shell rebuild on every ordinary anonymous boot -- see `App.kt`'s `refreshShell`
 * KDoc for the DOM-visible consequence that redundant rebuild used to have on a desktop viewport
 * (a real sidebar unmount/remount cycle, live-verified to leave Bootstrap's offcanvas backdrop
 * stuck over the login form).
 *
 * [AppState] is a singleton `object` -- state persists across test cases in the same JS engine
 * instance, so every test resets [AppState.session] back to `null` (via [AppState.setSession]
 * itself, its only mutator) and restores a no-op [AppState.onSessionChange] afterward, mirroring
 * the teardown discipline `BrandingTest`'s own `removeBrandElement()` `@AfterTest` establishes for
 * this module's other singleton/DOM state.
 */
class AppStateTest {
    @BeforeTest
    fun resetSession() {
        AppState.onSessionChange = {}
        AppState.setSession(null)
    }

    @AfterTest
    fun tearDown() {
        AppState.onSessionChange = {}
        AppState.setSession(null)
    }

    @Test
    fun settingTheSameNullSessionAgain_doesNotFireOnSessionChange() {
        var callCount = 0
        AppState.onSessionChange = { callCount++ }

        AppState.setSession(null)

        assertEquals(0, callCount)
        assertFalse(AppState.isAuthenticated)
    }

    @Test
    fun settingAnEqualSessionAgain_doesNotFireOnSessionChange() {
        val session = testSession()
        AppState.setSession(session)
        var callCount = 0
        AppState.onSessionChange = { callCount++ }

        // A structurally identical, but distinct, DTO instance -- exactly what a second RPC call
        // returning the same still-valid session would deserialize to.
        AppState.setSession(session.copy())

        assertEquals(0, callCount)
    }

    @Test
    fun settingADifferentSession_firesOnSessionChangeExactlyOnce() {
        var callCount = 0
        AppState.onSessionChange = { callCount++ }

        AppState.setSession(testSession())

        assertEquals(1, callCount)
        assertTrue(AppState.isAuthenticated)
    }

    @Test
    fun clearingARealSession_firesOnSessionChange() {
        AppState.setSession(testSession())
        var callCount = 0
        AppState.onSessionChange = { callCount++ }

        AppState.setSession(null)

        assertEquals(1, callCount)
        assertFalse(AppState.isAuthenticated)
    }

    @Test
    fun switchingBetweenTwoDifferentRealSessions_firesOnSessionChange() {
        AppState.setSession(testSession(memberId = "member-1"))
        var callCount = 0
        AppState.onSessionChange = { callCount++ }

        AppState.setSession(testSession(memberId = "member-2"))

        assertEquals(1, callCount)
    }
}
