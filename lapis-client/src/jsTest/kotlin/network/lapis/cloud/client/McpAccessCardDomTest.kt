package network.lapis.cloud.client

import kotlinx.browser.localStorage
import kotlinx.browser.sessionStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.McpAccessStateDto
import network.lapis.cloud.shared.domain.McpConnectionDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.McpFeatureDisabledException
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOKEN_A = "11111111-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val TOKEN_B = "22222222-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

private fun conn(
    id: String,
    label: String = "Claude Desktop",
    lastUsedAt: LocalDateTime? = null,
) = McpConnectionDto(tokenId = id, connectionLabel = label, grantedAt = LocalDateTime(2026, 9, 1, 10, 30), lastUsedAt = lastUsedAt)

private fun state(
    allowed: Boolean = true,
    vararg connections: McpConnectionDto,
) = McpAccessStateDto(featureEnabled = true, accessAllowed = allowed, connections = connections.toList())

private class FakeMcpRpc(
    var state: McpAccessStateDto,
) : McpAccessRpc {
    var getCalls = 0
    val setCalls = mutableListOf<Boolean>()
    val revokeCalls = mutableListOf<String>()
    var getFailure: Throwable? = null
    var setFailure: Throwable? = null
    var revokeFailure: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null

    /** `true`: a revoke of an unknown id answers with the unchanged state (the server ignores a foreign token). */
    override suspend fun getState(): McpAccessStateDto {
        getCalls++
        getFailure?.let { throw it }
        return state
    }

    override suspend fun setAllowed(allowed: Boolean): McpAccessStateDto {
        setCalls += allowed
        gate?.await()
        setFailure?.let {
            setFailure = null
            throw it
        }
        state = if (allowed) state.copy(accessAllowed = true) else state.copy(accessAllowed = false, connections = emptyList())
        return state
    }

    override suspend fun revoke(tokenId: String): McpAccessStateDto {
        revokeCalls += tokenId
        gate?.await()
        revokeFailure?.let {
            revokeFailure = null
            throw it
        }
        state = state.copy(connections = state.connections.filterNot { it.tokenId == tokenId })
        return state
    }
}

private class Dialog(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val extraLines: List<String>,
    val dangerNote: String?,
    val onConfirm: () -> Unit,
)

private class ConfirmRecorder : McpConfirm {
    val dialogs = mutableListOf<Dialog>()

    override fun show(
        title: String,
        message: String,
        confirmLabel: String,
        extraLines: List<String>,
        dangerNote: String?,
        onConfirm: () -> Unit,
    ) {
        dialogs += Dialog(title, message, confirmLabel, extraLines, dangerNote, onConfirm)
    }
}

/**
 * Welle V1.9.29 "KI-Zugang" -- [McpAccessCard] in a REAL mounted KVision root: silent hiding, the switch (never optimistic, state only
 * from the server answer), the two confirmations, the connection list with untrusted names, double-click protection and error handling
 * without server text.
 */
class McpAccessCardDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = formTest(block)

    private fun HTMLElement.checkbox(): HTMLInputElement = assertNotNull(querySelector("input[type=checkbox]") as? HTMLInputElement)

    private fun HTMLElement.revokeButtons(): List<HTMLElement> = allOf("button").filter { it.textContent?.trim() == "Widerrufen" }

    private fun HTMLElement.text(): String = textContent.orEmpty()

    private suspend fun settle() = delay(60)

    private inline fun withCard(
        rpc: FakeMcpRpc,
        confirm: ConfirmRecorder = ConfirmRecorder(),
        toasts: MutableList<String> = mutableListOf(),
        id: String,
        crossinline block: suspend (McpAccessCard, () -> HTMLElement, ConfirmRecorder, MutableList<String>) -> Unit,
    ): Promise<Unit> =
        test {
            mountedForm(id) { root, element ->
                val card = McpAccessCard(parent = root, rpc = rpc, confirm = confirm, toast = { toasts += it })
                card.reload()
                settle()
                block(card, element, confirm, toasts)
            }
        }

    @Test
    fun featureDisabledException_hidesTheCardSilently(): Promise<Unit> {
        val rpc = FakeMcpRpc(state()).apply { getFailure = McpFeatureDisabledException() }
        return withCard(rpc, id = "mcp-disabled-ex") { card, _, _, toasts ->
            assertFalse(card.root.visible)
            assertTrue(toasts.isEmpty())
        }
    }

    @Test
    fun featureEnabledFalse_hidesTheCard(): Promise<Unit> {
        val rpc = FakeMcpRpc(McpAccessStateDto(featureEnabled = false, accessAllowed = true, connections = emptyList()))
        return withCard(rpc, id = "mcp-disabled-flag") { card, _, _, toasts ->
            assertFalse(card.root.visible)
            assertTrue(toasts.isEmpty())
        }
    }

    @Test
    fun forbiddenOnLoad_hidesTheCardWithoutAToast(): Promise<Unit> {
        val rpc = FakeMcpRpc(state()).apply { getFailure = ForbiddenException() }
        return withCard(rpc, id = "mcp-forbidden-load") { card, _, _, toasts ->
            assertFalse(card.root.visible)
            assertTrue(toasts.isEmpty())
        }
    }

    @Test
    fun theSessionGate_noRpcAndNoCardWithoutMcpOrForANonMember(): Promise<Unit> =
        test {
            fun session(
                mcpEnabled: Boolean,
                status: MemberStatus,
            ) = SessionInfoDto(
                memberId = "member-1",
                displayName = "Dana Keller",
                role = AccountRole.MEMBER,
                status = status,
                expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
                mcpEnabled = mcpEnabled,
            )
            val closedSessions =
                listOf(session(false, MemberStatus.ACTIVE), session(true, MemberStatus.GUEST), session(true, MemberStatus.FRIEND))
            closedSessions.forEach { s ->
                AppState.setSession(s)
                val rpc = FakeMcpRpc(state())
                mountedForm("mcp-gate-${s.status}-${s.mcpEnabled}") { root, element ->
                    renderMcpAccessSection(root, rpc)
                    settle()
                    assertEquals(0, rpc.getCalls, "no RPC for $s")
                    assertFalse(element().text().contains("KI-Zugang"), "no card for $s")
                }
            }
            AppState.setSession(session(true, MemberStatus.ACTIVE))
            val rpc = FakeMcpRpc(state())
            mountedForm("mcp-gate-open") { root, element ->
                renderMcpAccessSection(root, rpc)
                awaitUntil("card rendered") { element().text().contains("KI-Zugang") && rpc.getCalls == 1 }
            }
        }

    @Test
    fun switchedOffAndEmpty_showsTheOffLine(): Promise<Unit> =
        withCard(FakeMcpRpc(state(allowed = false)), id = "mcp-off") { card, element, _, _ ->
            assertTrue(card.root.visible)
            assertFalse(element().checkbox().checked)
            assertTrue(element().text().contains("Ausgeschaltet."))
            assertEquals(0, element().revokeButtons().size)
        }

    @Test
    fun switchedOn_withoutAndWithConnections_showsBothLinesAndKeepsServerOrder(): Promise<Unit> =
        withCard(FakeMcpRpc(state(true)), id = "mcp-on-empty") { _, element, _, _ ->
            assertTrue(element().checkbox().checked)
            assertTrue(element().text().contains("Eingeschaltet. Derzeit ist kein Agent verbunden."))
        }.then {
            withCard(FakeMcpRpc(state(true, conn(TOKEN_A, "Erster"), conn(TOKEN_B, "Zweiter"))), id = "mcp-on-two") { _, element, _, _ ->
                val text = element().text()
                assertTrue(text.contains("Eingeschaltet. Verbundene Agenten: 2."))
                assertTrue(text.indexOf("Erster") in 0 until text.indexOf("Zweiter"), "server order kept")
                assertEquals(2, element().revokeButtons().size)
            }
        }

    @Test
    fun switchingOff_withConnections_confirmCallsOnceAndEmptiesTheList(): Promise<Unit> {
        val rpc = FakeMcpRpc(state(true, conn(TOKEN_A), conn(TOKEN_B)))
        return withCard(rpc, id = "mcp-off-confirm") { _, element, confirm, _ ->
            element().checkbox().click()
            settle()
            assertEquals(1, confirm.dialogs.size)
            val dialog = confirm.dialogs.single()
            assertTrue(dialog.extraLines[0].contains("sofort getrennt"))
            assertNotNull(dialog.dangerNote)
            assertTrue(element().checkbox().checked, "the switch shows the server state until confirmed")
            assertEquals(0, rpc.setCalls.size)
            dialog.onConfirm()
            awaitUntil("switched off") { rpc.setCalls == listOf(false) && !element().checkbox().checked }
            assertEquals(0, element().revokeButtons().size)
            assertTrue(element().text().contains("Ausgeschaltet."))
        }
    }

    @Test
    fun switchingOff_cancel_callsNothingAndTheSwitchStaysOn(): Promise<Unit> {
        val rpc = FakeMcpRpc(state(true, conn(TOKEN_A)))
        return withCard(rpc, id = "mcp-off-cancel") { _, element, confirm, _ ->
            element().checkbox().click()
            settle()
            assertEquals(1, confirm.dialogs.size)
            settle()
            assertEquals(0, rpc.setCalls.size)
            assertTrue(element().checkbox().checked)
        }
    }

    @Test
    fun switchingOff_withoutConnections_stillAsksAndSaysNobodyIsConnected(): Promise<Unit> {
        val rpc = FakeMcpRpc(state(true))
        return withCard(rpc, id = "mcp-off-empty") { _, element, confirm, _ ->
            element().checkbox().click()
            settle()
            val dialog = confirm.dialogs.single()
            assertEquals("Derzeit ist kein Agent verbunden.", dialog.extraLines[0].substringAfter("###KvI18nS###"))
            assertNull(dialog.dangerNote)
            assertTrue(dialog.extraLines[1].contains("Entwürfe bleiben erhalten"))
        }
    }

    @Test
    fun switchingOn_needsNoDialog(): Promise<Unit> {
        val rpc = FakeMcpRpc(state(false))
        return withCard(rpc, id = "mcp-on") { _, element, confirm, _ ->
            element().checkbox().click()
            awaitUntil("switched on") { rpc.setCalls == listOf(true) && element().checkbox().checked }
            assertEquals(0, confirm.dialogs.size)
        }
    }

    @Test
    fun switchingOn_rateLimited_showsTheHintAndReloadsTheServerState(): Promise<Unit> {
        val rpc = FakeMcpRpc(state(false)).apply { setFailure = ForbiddenException("sentinel-rate-limit-text") }
        return withCard(rpc, id = "mcp-on-limited") { _, element, _, toasts ->
            element().checkbox().click()
            awaitUntil("hint shown") { toasts.any { it.contains("einigen Minuten") } }
            awaitUntil("state re-read") { rpc.getCalls == 2 }
            assertFalse(element().checkbox().checked, "the switch shows the server state")
            assertFalse(toasts.any { it.contains("sentinel-rate-limit-text") })
        }
    }

    @Test
    fun revoke_cancelCallsNothing_confirmRemovesTheRow_andTheDialogHasNoLabel(): Promise<Unit> {
        val rpc = FakeMcpRpc(state(true, conn(TOKEN_A, "Streng Geheimer Name"), conn(TOKEN_B, "Zweiter")))
        return withCard(rpc, id = "mcp-revoke") { _, element, confirm, _ ->
            element().revokeButtons().first().click()
            settle()
            val dialog = confirm.dialogs.single()
            assertEquals(0, rpc.revokeCalls.size, "opening the dialog changes nothing")
            val dialogText =
                listOf(dialog.title, dialog.message, dialog.confirmLabel).joinToString(" ") + dialog.extraLines + dialog.dangerNote
            assertFalse(dialogText.contains("Streng Geheimer Name"), "no label in the dialog")
            assertFalse(dialogText.contains(TOKEN_A), "no token id in the dialog")
            dialog.onConfirm()
            awaitUntil("row gone") { rpc.revokeCalls == listOf(TOKEN_A) && !element().text().contains("Streng Geheimer Name") }
            assertTrue(element().text().contains("Zweiter"))
        }
    }

    @Test
    fun doubleClick_onConfirmTwice_callsTheServerOnce(): Promise<Unit> {
        val rpc = FakeMcpRpc(state(true, conn(TOKEN_A))).apply { gate = CompletableDeferred() }
        return withCard(rpc, id = "mcp-double-revoke") { _, element, confirm, _ ->
            element().revokeButtons().single().click()
            settle()
            val dialog = confirm.dialogs.single()
            dialog.onConfirm()
            dialog.onConfirm()
            settle()
            assertEquals(1, rpc.revokeCalls.size)
            rpc.gate?.complete(Unit)
            awaitUntil("row gone") { element().revokeButtons().isEmpty() }
        }
    }

    @Test
    fun doubleClick_disableOnConfirmTwice_callsTheServerOnce(): Promise<Unit> {
        val rpc = FakeMcpRpc(state(true, conn(TOKEN_A))).apply { gate = CompletableDeferred() }
        return withCard(rpc, id = "mcp-double-off") { _, element, confirm, _ ->
            element().checkbox().click()
            settle()
            val dialog = confirm.dialogs.single()
            dialog.onConfirm()
            dialog.onConfirm()
            settle()
            assertEquals(1, rpc.setCalls.size)
            assertTrue(element().checkbox().disabled, "the switch is locked while the write runs")
            rpc.gate?.complete(Unit)
            awaitUntil("unlocked") { !element().checkbox().disabled && !element().checkbox().checked }
        }
    }

    @Test
    fun revoke_ofAnUnknownToken_rendersTheAnswerWithoutAnError(): Promise<Unit> {
        val rpc = FakeMcpRpc(state(true, conn(TOKEN_A)))
        return withCard(rpc, id = "mcp-revoke-unknown", toasts = mutableListOf()) { _, element, confirm, toasts ->
            element().revokeButtons().single().click()
            settle()
            rpc.state = state(true, conn(TOKEN_A)) // the server removed nothing: the token was not the caller's
            rpc.revokeFailure = null
            confirm.dialogs.single().onConfirm()
            awaitUntil("answered") { rpc.revokeCalls.size == 1 }
            settle()
            assertTrue(toasts.isEmpty())
        }
    }

    @Test
    fun revoke_conflict_showsANeutralToastWithoutTheExceptionText_andReloads(): Promise<Unit> {
        val rpc = FakeMcpRpc(state(true, conn(TOKEN_A))).apply { revokeFailure = ConflictException("sentinel-conflict-text") }
        return withCard(rpc, id = "mcp-revoke-conflict") { _, element, confirm, toasts ->
            element().revokeButtons().single().click()
            settle()
            confirm.dialogs.single().onConfirm()
            awaitUntil("toast") { toasts.any { it.contains("konnte nicht gespeichert werden") } }
            awaitUntil("reloaded") { rpc.getCalls == 2 }
            assertFalse(toasts.any { it.contains("sentinel-conflict-text") })
        }
    }

    @Test
    fun aMaliciousLabel_isPlainText_neverAMarker_neverAnElement(): Promise<Unit> {
        val evil = "###KvI18nS###Abbrechen<img src=x onerror=alert(1)>"
        val rpc = FakeMcpRpc(state(true, conn(TOKEN_A, evil), conn(TOKEN_B, "   ")))
        return withCard(rpc, id = "mcp-evil-label") { _, element, _, _ ->
            val text = element().text()
            assertFalse(text.contains("###KvI18nS###"), "marker stripped")
            assertTrue(text.contains("Abbrechen<img src=x onerror=alert(1)>"), "shown as literal text")
            assertNull(element().querySelector("img"), "no element created from the label")
            assertTrue(text.contains("Ohne Namen"), "a blank label gets the fallback")
        }
    }

    @Test
    fun lastUsed_neverOrAtADate(): Promise<Unit> {
        val rpc = FakeMcpRpc(state(true, conn(TOKEN_A, "Nie"), conn(TOKEN_B, "Schon", LocalDateTime(2026, 9, 20, 8, 15))))
        return withCard(rpc, id = "mcp-last-used") { _, element, _, _ ->
            val text = element().text()
            assertTrue(text.contains("Noch nie benutzt"))
            assertTrue(text.contains("Zuletzt benutzt am"))
            assertTrue(text.contains("Erteilt am"))
        }
    }

    @Test
    fun loadError_showsTheErrorStateWithRetry_andRetryRenders(): Promise<Unit> {
        val rpc = FakeMcpRpc(state(true, conn(TOKEN_A))).apply { getFailure = IllegalStateException("sentinel-load-text") }
        return withCard(rpc, id = "mcp-load-error") { card, element, _, toasts ->
            assertTrue(card.root.visible)
            assertTrue(element().text().contains("Erneut versuchen"))
            assertFalse(element().text().contains("sentinel-load-text"))
            assertTrue(toasts.isEmpty())
            rpc.getFailure = null
            element().buttonNamed("Erneut versuchen").click()
            awaitUntil("rendered") { rpc.getCalls == 2 && element().revokeButtons().size == 1 }
        }
    }

    @Test
    fun theTokenId_isNeverInTheDomOrInStorage(): Promise<Unit> {
        val rpc = FakeMcpRpc(state(true, conn(TOKEN_A), conn(TOKEN_B)))
        return withCard(rpc, id = "mcp-no-token-id") { card, _, _, _ ->
            val html = card.root.getElement()!!.outerHTML
            assertFalse(html.contains(TOKEN_A) || html.contains(TOKEN_B), "token id in the DOM")
            val stored =
                (0 until localStorage.length).mapNotNull { localStorage.key(it) } +
                    (0 until sessionStorage.length).mapNotNull { sessionStorage.key(it) }
            val values = stored.map { localStorage.getItem(it).orEmpty() + sessionStorage.getItem(it).orEmpty() }
            assertFalse(values.any { it.contains(TOKEN_A) || it.contains(TOKEN_B) }, "token id in browser storage")
        }
    }

    @Test
    fun anUnexpectedWriteError_showsAFixedSentence_neverTheMessage(): Promise<Unit> {
        val rpc = FakeMcpRpc(state(true, conn(TOKEN_A))).apply { revokeFailure = IllegalStateException("sentinel-write-text") }
        return withCard(rpc, id = "mcp-write-error") { card, element, confirm, toasts ->
            element().revokeButtons().single().click()
            settle()
            confirm.dialogs.single().onConfirm()
            awaitUntil("toast") { toasts.isNotEmpty() }
            assertFalse(toasts.any { it.contains("sentinel-write-text") })
            assertFalse(
                card.root
                    .getElement()!!
                    .outerHTML
                    .contains("sentinel-write-text"),
            )
        }
    }

    @Test
    fun theRevokeButtonOfAConnection_carriesTheRevokeIcon_andKeepsItsName(): Promise<Unit> =
        withCard(FakeMcpRpc(state(true, conn("t1"))), id = "mcp-revoke-icon") { _, element, _, _ ->
            assertButtonIcon(element(), "Widerrufen", "fa-ban")
        }
}
