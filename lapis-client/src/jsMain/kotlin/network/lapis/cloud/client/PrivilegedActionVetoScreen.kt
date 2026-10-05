package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px

/**
 * Welle V1.9.57 "Admin-Peer-Schutz" -- the destination of the objection link in the mail to the TARGET of a temporary-password request
 * (`#/privileged-action-veto?token=...`). Same discipline as the address-change links ([renderRevokeEmailChangeScreen]):
 *  - **Nothing is sent on load.** Mail clients and link scanners open links; only a click on the button sends the POST.
 *  - **The token leaves the URL at once** ([stripTokenFromUrl]): it lives in a local variable from then on.
 *  - **No server text is ever shown, and the server's answer is the same for every token** (no enumeration): the sentence after the click
 *    says only that the objection was transmitted and that an open request is ended -- it never claims that a request existed.
 *  - After the click there is a link to the sign-in page, never an automatic redirect.
 */
fun renderPrivilegedActionVetoScreen(
    container: SimplePanel,
    token: String?,
) {
    val root =
        container.vPanel(spacing = 10) {
            addCssClass("mx-auto")
            maxWidth = AUTH_CARD_MAX_WIDTH_PX.px
            width = 100.perc
            marginTop = 64.px
        }
    root.brandLockup()
    root.pageHeader(tr("Passwortvergabe widersprechen?"))

    if (token.isNullOrBlank()) {
        root.p(tr("Dieser Link ist ungültig oder abgelaufen."))
        root.link(tr("Zur Anmeldung"), url = "#${Routes.LOGIN}")
        return
    }
    // From here on the token exists only in this closure.
    stripTokenFromUrl(Routes.PRIVILEGED_ACTION_VETO)

    root.p(
        tr(
            "Für Ihr Administratorkonto wurde ein temporäres Passwort beantragt. Wenn Sie das nicht erwartet haben, legen Sie hier " +
                "Widerspruch ein. Ein offener Antrag wird dann beendet.",
        ),
    )
    val form = root.lapisForm()
    val button = newActionButton(ActionIcon.REJECT, tr("Widerspruch einlegen"), ButtonStyle.OUTLINEDANGER)
    form.buttons(primary = null, destructive = button)
    button.onClick {
        form.submit(button) {
            when (AuthHttp.vetoPrivilegedAction(token)) {
                PrivilegedActionVetoOutcome.SENT -> {
                    form.clearFormError()
                    button.disabled = true
                    root.p(tr("Ihr Widerspruch wurde übermittelt. Ein offener Antrag ist damit beendet."))
                    root.link(tr("Zur Anmeldung"), url = "#${Routes.LOGIN}")
                    notifySuccess(tr("Widerspruch übermittelt."))
                }
                PrivilegedActionVetoOutcome.RATE_LIMITED ->
                    form.showFormError(tr("Zu viele Versuche. Bitte versuchen Sie es später erneut."))
                PrivilegedActionVetoOutcome.FAILED ->
                    form.showFormError(tr("Die Anfrage ist fehlgeschlagen. Bitte versuchen Sie es später erneut."))
            }
        }
    }
}
