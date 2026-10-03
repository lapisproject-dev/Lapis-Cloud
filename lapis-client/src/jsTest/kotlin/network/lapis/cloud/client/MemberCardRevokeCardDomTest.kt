package network.lapis.cloud.client

import io.kvision.html.div
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberCardReissueResultDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ConflictException
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FakeRevokeRpc(
    var revoked: Boolean = true,
) : MemberCardRevokeRpc {
    val calls = mutableListOf<String>()
    var failWith: Throwable? = null

    override suspend fun revoke(memberId: String): MemberCardReissueResultDto {
        calls += memberId
        failWith?.let { throw it }
        return MemberCardReissueResultDto(
            memberId = memberId,
            memberNumber = "M-2026-0042",
            issuedAt = LocalDateTime(2026, 10, 2, 9, 0),
            previousCardRevoked = revoked,
        )
    }
}

private class RevokeDialogs : MemberCardRevokeConfirm {
    class Shown(
        val title: String,
        val message: String,
        val confirmLabel: String,
        val onConfirm: () -> Unit,
    )

    val shown = mutableListOf<Shown>()

    override fun show(
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit,
    ) {
        shown += Shown(title, message, confirmLabel, onConfirm)
    }
}

/** Welle V1.9.33 -- [MemberCardRevokeCard]: eligibility, the confirmation text, the result texts and the single-shot guard. */
class MemberCardRevokeCardDomTest {
    private fun session(
        status: MemberStatus = MemberStatus.ACTIVE,
        isGuest: Boolean = false,
    ) = SessionInfoDto(
        memberId = "member-1",
        displayName = "Dana Keller",
        role = AccountRole.MEMBER,
        status = status,
        expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        isGuest = isGuest,
    )

    private inline fun withCard(
        id: String,
        rpc: FakeRevokeRpc = FakeRevokeRpc(),
        dialogs: RevokeDialogs = RevokeDialogs(),
        sessionInfo: SessionInfoDto = session(),
        crossinline block: suspend (() -> HTMLElement, RevokeDialogs) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(sessionInfo)
            mountedForm(id) { root, element ->
                renderMemberCardRevokeSection(root, rpc, dialogs, cooldownMs = 10)
                block(element, dialogs)
            }
        }

    @Test
    fun friendsAndApplicantsAndGuests_getNoCard(): Promise<Unit> =
        formTest {
            listOf(session(MemberStatus.FRIEND), session(MemberStatus.APPLICATION), session(isGuest = true)).forEachIndexed { i, s ->
                AppState.setSession(s)
                mountedForm("revoke-none-$i") { root, element ->
                    root.div("davor")
                    renderMemberCardRevokeSection(root, FakeRevokeRpc(), RevokeDialogs())
                    delay(40)
                    assertFalse(element().textContent.orEmpty().contains("Mitgliedsausweis"), s.status.name)
                }
            }
        }

    @Test
    fun theConfirmation_explainsThatNoNewCardIsIssued_andCancelPerformsNoCall(): Promise<Unit> {
        val rpc = FakeRevokeRpc()
        return withCard("revoke-dialog", rpc) { el, dialogs ->
            el().buttonNamed("Ausweis sperren …").click()
            val dialog = dialogs.shown.single()
            assertTrue(dialog.message.contains("keinen neuen Ausweis"))
            assertTrue(dialog.message.contains("auf der Startseite herunterladen"))
            assertEquals("Ausweis sperren", dialog.confirmLabel)
            delay(40)
            assertTrue(rpc.calls.isEmpty(), "opening (and not confirming) the dialog must not call the server")
        }
    }

    @Test
    fun confirming_showsTheResultText_neverTheCardNumber(): Promise<Unit> {
        val rpc = FakeRevokeRpc(revoked = true)
        return withCard("revoke-ok", rpc) { el, dialogs ->
            el().buttonNamed("Ausweis sperren …").click()
            dialogs.shown.single().onConfirm()
            awaitUntil("status") { el().textContent.orEmpty().contains("Ihr Ausweis wurde gesperrt.") }
            assertEquals(listOf("member-1"), rpc.calls)
            assertFalse(el().textContent.orEmpty().contains("M-2026"))
        }
    }

    @Test
    fun withoutAPreviousCard_theTextSaysSo(): Promise<Unit> =
        withCard("revoke-none-valid", FakeRevokeRpc(revoked = false)) { el, dialogs ->
            el().buttonNamed("Ausweis sperren …").click()
            dialogs.shown.single().onConfirm()
            awaitUntil("status") { el().textContent.orEmpty().contains("Es war kein gültiger Ausweis vorhanden.") }
        }

    @Test
    fun aConflict_showsOneGeneralMessage(): Promise<Unit> {
        val rpc = FakeRevokeRpc().apply { failWith = ConflictException("Zu viele Ausweis-Anfragen") }
        val toasts = mutableListOf<String>()
        return formTest {
            AppState.setSession(session())
            mountedForm("revoke-conflict") { root, element ->
                val dialogs = RevokeDialogs()
                val card = MemberCardRevokeCard(root, "member-1", rpc, dialogs, { toasts += it }, 10)
                assertNotNull(card.revokeButton)
                element().buttonNamed("Ausweis sperren …").click()
                dialogs.shown.single().onConfirm()
                awaitUntil("toast") { toasts.isNotEmpty() }
                assertTrue(toasts.single().contains("gerade nicht gesperrt"))
                assertFalse(toasts.single().contains("Zu viele"))
            }
        }
    }

    @Test
    fun aDoubleConfirm_causesOneCall(): Promise<Unit> {
        val rpc = FakeRevokeRpc()
        return withCard("revoke-double", rpc) { el, dialogs ->
            el().buttonNamed("Ausweis sperren …").click()
            val confirm = dialogs.shown.single().onConfirm
            confirm()
            confirm()
            awaitUntil("done") { el().textContent.orEmpty().contains("Ihr Ausweis wurde gesperrt.") }
            assertEquals(1, rpc.calls.size)
        }
    }

    @Test
    fun theRevokeButton_carriesTheRevokeIcon_andKeepsItsName(): Promise<Unit> =
        withCard("revoke-icon", FakeRevokeRpc()) { el, _ ->
            assertButtonIcon(el(), "Ausweis sperren …", "fa-ban")
        }
}
