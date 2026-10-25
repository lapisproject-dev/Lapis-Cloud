package network.lapis.cloud.client

import io.kvision.i18n.I18n
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.LoginMethod
import network.lapis.cloud.shared.domain.MemberAdminSort
import org.w3c.dom.Element
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val RAW_ISO_DATE_TIME = Regex("""\d{4}-\d{2}-\d{2}T""")

/** V1.9.212 -- DOM safety net: no table cell of [root] shows a raw i18n token or a raw ISO date-time. */
internal fun assertNoRawTemporalInCells(root: Element) {
    val cells = root.querySelectorAll("td, th")
    for (i in 0 until cells.length) {
        val text = cells.item(i)?.textContent.orEmpty()
        assertFalse(text.contains(KV_I18N_MARKER), "i18n marker leaked into a cell: $text")
        assertFalse(text.contains(I18N_VALUE_SENTINEL), "value sentinel leaked into a cell: $text")
        assertFalse(RAW_ISO_DATE_TIME.containsMatchIn(text), "raw ISO date-time in a cell: $text")
    }
}

/** V1.9.207 / V1.9.212 -- the roster column "Letzte Anmeldung": plain text, rendered cell, no raw ISO string. */
class MemberAdminLastLoginUiTest {
    private val nbsp = " "
    private val summer = LocalDateTime(2026, 10, 24, 18, 11, 2, 793482000)
    private val winter = LocalDateTime(2026, 11, 2, 18, 11)
    private var previousLanguage = ""

    @BeforeTest
    fun setUp() {
        previousLanguage = I18n.language
        I18n.language = "de"
        OrganizationTime.zoneId = "Europe/Berlin"
    }

    @AfterTest
    fun tearDown() {
        OrganizationTime.zoneId = DEFAULT_ORGANIZATION_ZONE_ID
        I18n.language = previousLanguage
    }

    @Test
    fun plainText_isNullWhenNeverSignedIn() {
        assertNull(lastLoginPlainText(null, null))
        assertNull(lastLoginPlainText(null, LoginMethod.PASSWORD))
    }

    @Test
    fun plainText_isFormattedInTheOrganizationZone() {
        assertEquals("24.10.2026,${nbsp}20:11 · Passwort", lastLoginPlainText(summer, LoginMethod.PASSWORD))
        assertEquals("24.10.2026,${nbsp}20:11 · Zentrale Anmeldung", lastLoginPlainText(summer, LoginMethod.KEYCLOAK))
        assertEquals("24.10.2026,${nbsp}20:11 · Partner-Server", lastLoginPlainText(summer, LoginMethod.OIDC_FEDERATION))
    }

    @Test
    fun plainText_followsDaylightSavingTime() {
        assertEquals("02.11.2026,${nbsp}19:11 · Passwort", lastLoginPlainText(winter, LoginMethod.PASSWORD))
    }

    @Test
    fun plainText_showsOnlyTheDateWhenTheMethodIsMissing() {
        assertEquals("24.10.2026,${nbsp}20:11", lastLoginPlainText(summer, null))
    }

    @Test
    fun cell_rendersResolvedTextWithoutRawIsoOrMarker() {
        withMountedRoot("last-login-cell-test") { root, element ->
            root.lastLoginCell(summer, LoginMethod.PASSWORD)
            val spans = element().querySelectorAll("span")
            val text = (0 until spans.length).joinToString("") { spans.item(it)?.textContent.orEmpty() }
            assertEquals("24.10.2026,${nbsp}20:11 · Passwort", text)
            assertFalse(RAW_ISO_DATE_TIME.containsMatchIn(text), text)
            assertFalse(text.contains(KV_I18N_MARKER) || text.contains(I18N_VALUE_SENTINEL), text)
            assertTrue(element().querySelectorAll(".text-body-secondary").length >= 1)
        }
    }

    @Test
    fun cell_neverSignedInIsMuted() {
        withMountedRoot("last-login-cell-never-test") { root, element ->
            root.lastLoginCell(null, null)
            val muted = element().querySelector(".text-body-secondary")
            assertEquals("nie angemeldet", muted?.textContent)
        }
    }

    @Test
    fun cell_datePartFollowsALanguageSwitch() {
        withMountedRoot("last-login-cell-language-test") { root, element ->
            root.lastLoginCell(summer, null)
            val german = element().textContent.orEmpty()
            I18n.language = "en"
            val text = element().textContent.orEmpty()
            assertTrue(german.contains("24.10.2026"), german)
            val expected = formatSystemDateTime(summer)
            assertTrue(text.contains(expected), "expected the re-formatted date '$expected' in: $text")
            assertFalse(text.contains("24.10.2026"), "date part must be reformatted after the language switch: $text")
            assertFalse(RAW_ISO_DATE_TIME.containsMatchIn(text), text)
            assertFalse(text.contains(KV_I18N_MARKER), text)
        }
    }

    @Test
    fun methodLabels() {
        assertEquals("Passwort", loginMethodLabel(LoginMethod.PASSWORD))
        assertEquals("Zentrale Anmeldung", loginMethodLabel(LoginMethod.KEYCLOAK))
        assertEquals("Partner-Server", loginMethodLabel(LoginMethod.OIDC_FEDERATION))
    }

    @Test
    fun sortRoundtrip_coversAllSixValues() {
        MemberAdminSort.entries.forEach { assertEquals(it, it.toSortState().toMemberAdminSort(), it.name) }
        assertEquals(6, MemberAdminSort.entries.size)
    }
}
