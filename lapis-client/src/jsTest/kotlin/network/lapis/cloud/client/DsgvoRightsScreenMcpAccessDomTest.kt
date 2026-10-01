package network.lapis.cloud.client

import io.kvision.html.div
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.McpAccessStateDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.9.29 "KI-Zugang" -- the mounting contract of [renderMcpAccessSection], which `DsgvoRightsScreen` calls right after the public
 * profile card (the source position is pinned by `ClientMcpAccessCardTripwireTest`): the card is appended to the container it is given, and only
 * for an active member of a server whose MCP layer is on -- otherwise the container stays untouched and no request goes out.
 */
class DsgvoRightsScreenMcpAccessDomTest {
    private fun session(mcpEnabled: Boolean) =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = AccountRole.MEMBER,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            mcpEnabled = mcpEnabled,
        )

    private class CountingRpc : McpAccessRpc {
        var reads = 0

        override suspend fun getState(): McpAccessStateDto {
            reads++
            return McpAccessStateDto(featureEnabled = true, accessAllowed = true, connections = emptyList())
        }

        override suspend fun setAllowed(allowed: Boolean) = getState()

        override suspend fun revoke(tokenId: String) = getState()
    }

    @Test
    fun theCard_isAppendedAfterWhatIsAlreadyThere(): Promise<Unit> =
        formTest {
            AppState.setSession(session(mcpEnabled = true))
            val rpc = CountingRpc()
            mountedForm("dsgvo-mcp-order") { root, element ->
                root.div("Vorheriger Abschnitt")
                renderMcpAccessSection(root, rpc)
                awaitUntil("card rendered") { element().textContent.orEmpty().contains("KI-Zugang") }
                val text = element().textContent.orEmpty()
                assertTrue(text.indexOf("Vorheriger Abschnitt") < text.indexOf("KI-Zugang"))
                assertEquals(1, rpc.reads)
            }
        }

    @Test
    fun withoutMcpOnTheServer_nothingIsMountedAndNothingIsRequested(): Promise<Unit> =
        formTest {
            AppState.setSession(session(mcpEnabled = false))
            val rpc = CountingRpc()
            mountedForm("dsgvo-mcp-off") { root, element ->
                renderMcpAccessSection(root, rpc)
                kotlinx.coroutines.delay(80)
                assertEquals(0, rpc.reads)
                assertFalse(element().textContent.orEmpty().contains("KI-Zugang"))
            }
        }
}
