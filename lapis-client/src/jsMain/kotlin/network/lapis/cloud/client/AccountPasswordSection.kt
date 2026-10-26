package network.lapis.cloud.client

import io.kvision.html.Autocomplete
import io.kvision.html.ButtonStyle
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import network.lapis.cloud.shared.rpc.IAuthService

/**
 * W6c ("W4d form migration"): `lapisForm` replacement of the previous hand-built three-field block
 * with a manual `errorBox`. Inline on "Meine Daten" (V1.9.220, formerly the dashboard), not a modal -- there was never a second
 * entry point that a modal would need to reconcile with. `email` is the self-email check of
 * [FormRules.newPassword]: [network.lapis.cloud.shared.domain.SessionInfoDto] carries no email field
 * (only [Validation]'s length rule runs today), a weaker check than [RegistrationScreen]'s -- named
 * here, not silently worked around.
 */
internal fun renderChangePassword(root: SimplePanel) {
    root.h2(tr("Passwort ändern")) { addCssClass("h5") }
    val panel = root.vPanel(spacing = 6)
    val email = "" // SessionInfoDto carries no email field -- see KDoc above.
    val form = panel.lapisForm()
    val current =
        form.passwordField(
            label = tr("Aktuelles Passwort"),
            required = true,
            autocomplete = Autocomplete.CURRENT_PASSWORD,
            reveal = true,
        )
    val next =
        form.passwordField(
            label = gettext("Neues Passwort (mind. %1 Zeichen)", Validation.PASSWORD_MIN_LENGTH),
            required = true,
            autocomplete = Autocomplete.NEW_PASSWORD,
            reveal = true,
            rule = { FormRules.newPassword(value = it, email = email) },
        )
    val confirm =
        form.passwordField(
            label = tr("Neues Passwort bestätigen"),
            required = true,
            autocomplete = Autocomplete.NEW_PASSWORD,
            revealedBy = next,
        )
    form.crossFieldRule(field = confirm) { FormRules.passwordsMatch(password = next.value, confirmation = confirm.value) }
    val save = newActionButton(ActionIcon.PASSWORD, tr("Passwort ändern"), ButtonStyle.PRIMARY)
    val cancel = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.PRIMARY)
    form.buttons(primary = save, cancel = cancel, enterSubmits = true)
    cancel.onClick { listOf(current, next, confirm).forEach { it.reset() } }
    save.onClick {
        form.submit(save) {
            val result = guarded { rpcService<IAuthService>().changePassword(current.value, next.value) }
            if (result != null) {
                notifySuccess(tr("Passwort geändert."))
                listOf(current, next, confirm).forEach { it.reset() }
            }
        }
    }
}
