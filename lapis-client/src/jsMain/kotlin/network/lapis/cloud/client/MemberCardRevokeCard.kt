package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.delay
import network.lapis.cloud.shared.domain.MemberCardReissueResultDto
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.IMemberService

/** The RPC surface [MemberCardRevokeCard] needs -- an interface so DOM tests can drive the card without a server. Exceptions propagate. */
internal interface MemberCardRevokeRpc {
    suspend fun revoke(memberId: String): MemberCardReissueResultDto
}

internal fun liveMemberCardRevokeRpc(): MemberCardRevokeRpc =
    object : MemberCardRevokeRpc {
        override suspend fun revoke(memberId: String) = rpcService<IMemberService>().reissueMemberCard(memberId)
    }

/** Injectable confirmation dialog (the tests drive it without a modal). */
internal fun interface MemberCardRevokeConfirm {
    fun show(
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit,
    )
}

internal val liveMemberCardRevokeConfirm =
    MemberCardRevokeConfirm { title, message, confirmLabel, onConfirm ->
        confirmDialog(
            title = title,
            message = message,
            confirmLabel = confirmLabel,
            confirmStyle = ButtonStyle.DANGER,
            focusCancel = true,
            onConfirm = onConfirm,
        )
    }

/**
 * Welle V1.9.33 -- "Ausweis sperren" on "Meine Daten": kills a lost card WITHOUT downloading a new one (the download on the start page
 * is the other way to invalidate a card and always issues a new one). The server call is `IMemberService.reissueMemberCard`; its result
 * is read ONLY through `previousCardRevoked` -- the card number and any code are never shown.
 *
 * Kilua RPC transmits only the type of an exception, so a rate limit and "no membership status" are both a `ConflictException`: the
 * message for it is one fixed, general sentence. After a confirmed attempt the button stays locked for a cooldown, so a second dialog
 * cannot immediately lock again; the server's shared rate limit remains the real brake.
 */
internal class MemberCardRevokeCard(
    parent: SimplePanel,
    private val memberId: String,
    private val rpc: MemberCardRevokeRpc,
    private val confirm: MemberCardRevokeConfirm = liveMemberCardRevokeConfirm,
    private val toast: (String) -> Unit = { notifyError(it) },
    private val cooldownMs: Long = MEMBER_CARD_REISSUE_COOLDOWN_MS,
) {
    internal val root: SimplePanel = parent.vPanel(spacing = 8) { addCssClasses("border rounded p-3") }
    internal val revokeButton: Button
    internal val statusLine: Div

    init {
        root.h2(tr("Mitgliedsausweis")) { addCssClass("h5") }
        root.div(
            tr(
                "Haben Sie Ihren Ausweis verloren? Dann können Sie ihn hier sperren, ohne einen neuen auszustellen.",
            ),
        ) { addCssClasses("text-muted small") }
        revokeButton = Button(tr("Ausweis sperren …"), style = ButtonStyle.OUTLINEDANGER)
        root.add(revokeButton)
        statusLine = root.div("") { addCssClasses("text-muted small") }
        statusLine.setAttribute("role", "status")
        statusLine.setAttribute("aria-live", "polite")
        revokeButton.onClick {
            if (revokeButton.disabled) return@onClick
            confirm.show(
                gettext("Ausweis sperren"),
                gettext(
                    "Ihr bisheriger Ausweis und sein QR-Code werden sofort ungültig. Sie erhalten dabei keinen neuen Ausweis. Einen neuen Ausweis können Sie jederzeit auf der Startseite herunterladen.",
                ),
                gettext("Ausweis sperren"),
            ) { revoke() }
        }
    }

    private fun revoke() {
        runGuardedAction(revokeButton) {
            var result: MemberCardReissueResultDto? = null
            val ok =
                runOrConflict(
                    conflictMessage = gettext("Der Ausweis konnte gerade nicht gesperrt werden. Bitte versuchen Sie es später erneut."),
                    toast = toast,
                ) { result = rpc.revoke(memberId) }
            if (ok) {
                statusLine.content =
                    if (result?.previousCardRevoked == true) {
                        gettext("Ihr Ausweis wurde gesperrt.")
                    } else {
                        gettext("Es war kein gültiger Ausweis vorhanden.")
                    }
            }
            delay(cooldownMs)
        }
    }
}

/** Mounts the card for an active organization member (same condition as the download card on the start page); nothing otherwise. */
internal fun renderMemberCardRevokeSection(
    container: SimplePanel,
    rpc: MemberCardRevokeRpc = liveMemberCardRevokeRpc(),
    confirm: MemberCardRevokeConfirm = liveMemberCardRevokeConfirm,
    cooldownMs: Long = MEMBER_CARD_REISSUE_COOLDOWN_MS,
) {
    val session = AppState.session ?: return
    if (session.isGuest || session.status !in MemberStatusSets.ORGANIZATION_MEMBER) return
    MemberCardRevokeCard(container, session.memberId, rpc, confirm, cooldownMs = cooldownMs)
}
