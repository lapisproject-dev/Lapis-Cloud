package network.lapis.cloud.client

import io.kvision.form.text.password
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import kotlinx.coroutines.launch

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
            maxWidth = 380.px
            width = 100.perc
            marginTop = 64.px
        }
    root.h1(tr("Neues Passwort setzen"))

    if (token.isNullOrBlank()) {
        root.p(tr("Dieser Link enthält keinen gültigen Token. Bitte fordern Sie einen neuen Link an."))
        root.link(tr("Zur Anmeldung"), url = "#${Routes.LOGIN}")
        return
    }

    root.p(tr("Bitte vergeben Sie Ihr neues Passwort."))
    val errorBox =
        root.div {
            addCssClass("text-danger")
            hide()
        }
    val newPassword = root.password(label = tr("Neues Passwort"))
    lateinit var confirmButton: io.kvision.html.Button
    confirmButton =
        root.button(tr("Neues Passwort setzen"), style = ButtonStyle.PRIMARY) {
            onClick {
                val pw = newPassword.value.orEmpty()
                errorBox.hide()
                if (!Validation.isNonBlank(pw)) {
                    errorBox.content = tr("Bitte ein neues Passwort eingeben.")
                    errorBox.show()
                    return@onClick
                }
                confirmButton.disabled = true
                AppScope.launch {
                    val error = AuthHttp.confirmPasswordReset(token, pw)
                    confirmButton.disabled = false
                    if (error != null) {
                        errorBox.content = error
                        errorBox.show()
                    } else {
                        notifySuccess(tr("Passwort wurde geändert -- bitte melden Sie sich neu an."))
                        navigateTo(Routes.LOGIN)
                    }
                }
            }
        }
}
