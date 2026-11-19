package network.lapis.cloud.client

import kotlinx.browser.document
import org.w3c.dom.HTMLScriptElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val BRAND_ID = "lapis-brand"

private fun setBrandPayload(json: String?) {
    document.getElementById(BRAND_ID)?.remove()
    if (json == null) return
    val script = document.createElement("script") as HTMLScriptElement
    script.type = "application/json"
    script.id = BRAND_ID
    script.textContent = json
    document.head?.appendChild(script)
}

/** V1.9.315 -- the one rule behind every "Mitglied werden" link, and how the brand payload feeds it. */
class MembershipApplicationVisibilityTest {
    @AfterTest
    fun cleanup() {
        setBrandPayload(null)
    }

    @Test
    fun theRule_isOpenOnlyWhenTheSwitchIsOnAndKeycloakModeIsOff() {
        assertTrue(isMembershipApplicationOpen(enabled = true, keycloakMode = false))
        assertFalse(isMembershipApplicationOpen(enabled = true, keycloakMode = true))
        assertFalse(isMembershipApplicationOpen(enabled = false, keycloakMode = false))
        assertFalse(isMembershipApplicationOpen(enabled = false, keycloakMode = true))
    }

    @Test
    fun payloadWithoutTheField_keepsTodaysBehaviour() {
        setBrandPayload("""{"title":"X","logoUrl":null,"keycloakMode":false,"emergencyAdminLoginEnabled":false}""")
        assertTrue(Branding.membershipApplicationEnabled)
        assertTrue(membershipApplicationOpenNow())
    }

    @Test
    fun brokenOrMissingPayload_keepsTodaysBehaviour() {
        setBrandPayload("{not json")
        assertTrue(Branding.membershipApplicationEnabled)
        setBrandPayload(null)
        assertTrue(Branding.membershipApplicationEnabled)
        assertTrue(membershipApplicationOpenNow())
    }

    @Test
    fun payloadSwitchOff_closesIt_andKeycloakModeClosesItToo() {
        setBrandPayload("""{"title":"X","logoUrl":null,"keycloakMode":false,"membershipApplicationEnabled":false}""")
        assertFalse(Branding.membershipApplicationEnabled)
        assertFalse(membershipApplicationOpenNow())
        setBrandPayload("""{"title":"X","logoUrl":null,"keycloakMode":true,"membershipApplicationEnabled":true}""")
        assertEquals(true, Branding.membershipApplicationEnabled)
        assertFalse(membershipApplicationOpenNow())
    }
}
