package network.lapis.cloud.client

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
 * `#/verify-email?token=...` link `MailTemplates.friendVerification` puts into the real FRIEND
 * email-verification mail. Unlike [renderPasswordResetScreen], there is no form to fill in -- the
 * confirmation call fires automatically on render, since the token alone is the whole payload
 * (`POST /api/auth/friend/verify-email` -- see `AuthRoutes.kt`).
 */
fun renderVerifyEmailScreen(
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
    root.h1(tr("E-Mail-Adresse bestätigen"))

    if (token.isNullOrBlank()) {
        root.p(tr("Dieser Link enthält keinen gültigen Token. Bitte fordern Sie einen neuen Link an."))
        root.link(tr("Zur Anmeldung"), url = "#${Routes.LOGIN}")
        return
    }

    val statusBox = root.p(tr("Bestätigung wird verarbeitet ..."))
    val errorBox =
        root.div {
            addCssClass("text-danger")
            hide()
        }

    AppScope.launch {
        val error = AuthHttp.confirmFriendEmailVerification(token)
        if (error != null) {
            statusBox.hide()
            errorBox.content = error
            errorBox.show()
        } else {
            statusBox.content = tr("Ihre E-Mail-Adresse wurde bestätigt.")
            notifySuccess(tr("E-Mail-Adresse bestätigt."))
        }
    }

    root.link(tr("Zur Anmeldung"), url = "#${Routes.LOGIN}")
}
