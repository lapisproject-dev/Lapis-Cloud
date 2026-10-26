package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.h2
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import network.lapis.cloud.shared.rpc.IRegistrationService

/** V1.9.220 -- leaving the organization, now the last self-service block of "Meine Daten" (it used to sit on the dashboard). */
internal fun renderLeaveMembership(root: SimplePanel) {
    root.h2(tr("Mitgliedschaft beenden")) { addCssClass("h5") }
    val exitButton = root.button(tr("Austritt (Mitgliedschaft beenden)"), style = ButtonStyle.OUTLINEDANGER)
    exitButton.onClick {
        // Audit fix M9: leaving is irreversible -- while the request runs the trigger is disabled ([runGuardedAction]), so no second
        // dialog can be opened (and confirmed) for a second `leaveMembership()`; the dialog itself is already single-shot ([ConfirmOnce]).
        if (exitButton.disabled) return@onClick
        confirmDialog(
            title = tr("Austritt bestätigen"),
            message =
                tr(
                    "Möchten Sie Ihre Mitgliedschaft wirklich beenden? Dies ist nicht rückgängig zu machen -- " +
                        "Sie werden abgemeldet und können sich nicht erneut mit diesem Konto anmelden.",
                ),
            confirmLabel = tr("Austritt bestätigen"),
        ) {
            runGuardedAction(exitButton) {
                val result = guarded { rpcService<IRegistrationService>().leaveMembership() }
                if (result != null) {
                    AppState.setSession(null)
                    notifyInfo(tr("Sie sind ausgetreten."))
                    navigateTo(Routes.LOGIN)
                }
            }
        }
    }
}
