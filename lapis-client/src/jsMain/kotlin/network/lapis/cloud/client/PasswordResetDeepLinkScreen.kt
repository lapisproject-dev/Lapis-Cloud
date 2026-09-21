package network.lapis.cloud.client

import io.kvision.html.Autocomplete
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.h1
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px

/**
 * V1.2.3 Echter SMTP-Versand, Option B "Client-Deep-Links" -- the destination of the
 * `#/password-reset?token=...` link `MailTemplates.passwordReset` puts into the real password-
 * reset mail. [token] is read out of the hash query string by [Routes.PASSWORD_RESET]'s route
 * handler in `Routing.kt` (never off `window.location.search`, see [hashQueryParam] KDoc). Complements
 * -- does NOT replace -- `LoginScreen.kt`'s own manual "Token (aus der E-Mail bzw. vom Betreiber)"
 * field, which stays as the fallback for an operator-communicated token.
 */
fun renderPasswordResetScreen(
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
    // V1.4.7 "Root-Verlinkung" -- Marken-Lockup über der Karte, siehe LoginScreen.kt für dasselbe
    // Muster. root.h1 bleibt unverändert der screenspezifische Titel.
    root.brandLockup()
    root.h1(tr("Neues Passwort setzen"))

    if (token.isNullOrBlank()) {
        root.p(tr("Dieser Link enthält keinen gültigen Token. Bitte fordern Sie einen neuen Link an."))
        root.link(tr("Zur Anmeldung"), url = "#${Routes.LOGIN}")
        return
    }

    root.p(tr("Bitte vergeben Sie Ihr neues Passwort."))
    val form = root.lapisForm()
    val newPassword =
        form.passwordField(
            label = tr("Neues Passwort"),
            required = true,
            autocomplete = Autocomplete.NEW_PASSWORD,
            hint = gettext("Mindestens %1 Zeichen.", Validation.PASSWORD_MIN_LENGTH),
            rule = { FormRules.newPassword(value = it, email = "") },
        )
    val confirmButton = Button(tr("Neues Passwort setzen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = confirmButton)
    confirmButton.onClick {
        form.submit(confirmButton) {
            val error = AuthHttp.confirmPasswordReset(token, newPassword.value)
            if (error != null) {
                form.showFormError(error)
            } else {
                notifySuccess(tr("Passwort wurde geändert -- bitte melden Sie sich neu an."))
                navigateTo(Routes.LOGIN)
            }
        }
    }
}
