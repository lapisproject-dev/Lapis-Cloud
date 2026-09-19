package network.lapis.cloud.client

import io.kvision.panel.SimplePanel
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Welle V1.4.21 -- Konstruktions-Smoke-Test der beiden neuen Screens: der In-Memory-Widget-Baum
 * (kein DOM-Mounting, gleiche Haltung wie [SidebarStructureTest]) muss für jede erlaubte Rolle ohne
 * Ausnahme aufgebaut werden. Die anschließenden RPCs scheitern im Testlauf mit 404 und werden von
 * `guarded {}` verschluckt (Toast-Container existiert im Test nicht) -- geprüft wird hier nur der
 * synchrone Aufbau (Rollen-Gates, Segment-/Filter-Verdrahtung, Formular-Vorbelegung).
 */
class OpenItemsScreenSmokeTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "m-1",
            displayName = "Test",
            role = role,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            status = MemberStatus.ACTIVE,
        )

    @AfterTest
    fun tearDown() {
        AppState.onSessionChange = {}
        AppState.setSession(null)
    }

    @Test
    fun openItemsScreen_buildsForEveryReadRole() {
        for (role in OpenItemAuthzUi.READ_ROLES) {
            AppState.setSession(session(role))
            val container = SimplePanel()
            renderOpenItemsScreen(container)
            assertTrue(container.getChildren().isNotEmpty(), "$role: expected the screen root to be added")
        }
    }

    @Test
    fun openItemsScreen_withADeepLinkParameter_buildsForTreasurer() {
        AppState.setSession(session(AccountRole.TREASURER))
        val container = SimplePanel()
        renderOpenItemsScreen(container, "0b8a1d2e-1c3f-4a5b-9c7d-1234567890ab")
        assertTrue(container.getChildren().isNotEmpty())
    }

    @Test
    fun openItemsScreen_ignoresAMalformedDeepLinkParameter() {
        AppState.setSession(session(AccountRole.BOARD))
        val container = SimplePanel()
        renderOpenItemsScreen(container, "<script>alert(1)</script>")
        assertTrue(container.getChildren().isNotEmpty())
    }

    @Test
    fun receivableDunningSettingsScreen_buildsForAdmin() {
        AppState.setSession(session(AccountRole.ADMIN))
        val container = SimplePanel()
        renderReceivableDunningSettingsScreen(container)
        assertTrue(container.getChildren().isNotEmpty())
    }

    @Test
    fun looksLikeOpenItemUuid_acceptsUuidsOnly() {
        assertTrue(looksLikeOpenItemUuid("0b8a1d2e-1c3f-4a5b-9c7d-1234567890ab"))
        assertTrue(!looksLikeOpenItemUuid("not-a-uuid"))
        assertTrue(!looksLikeOpenItemUuid("0b8a1d2e-1c3f-4a5b-9c7d-1234567890ab<b>"))
    }
}
