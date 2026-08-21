package network.lapis.cloud.client

import io.kvision.form.text.password
import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.rpc.IAuthService

/**
 * Screen 1 of the V0.7.3 plan. Calls the real `POST /api/auth/login` HTTP route (see [AuthHttp])
 * -- NOT an RPC method, see `IAuthService` KDoc for why. On success, immediately calls
 * `getSessionInfo()` (RPC, the session cookie is already set by then) to populate
 * [AppState.session] with the real role/expiry, rather than trusting the login response body.
 *
 * Error handling: the server's own response text is shown verbatim -- it is already
 * account-enumeration-hardened (identical generic text for unknown email / wrong password / a
 * departed-or-rejected account, see `AuthRoutes` KDoc), so this screen adds NO further
 * differentiation on top of it; doing so would defeat the server's own hardening.
 */
fun renderLoginScreen(container: SimplePanel) {
    container.vPanel(spacing = 10) {
        addCssClass("mx-auto")
        width = 380.px
        marginTop = 64.px

        h1("Lapis Cloud")
        p(tr("Bitte melden Sie sich mit Ihrer E-Mail-Adresse an."))

        val errorBox =
            div().apply {
                addCssClass("text-danger")
                hide()
            }

        val emailInput = text(type = InputType.EMAIL, label = tr("E-Mail"))
        val passwordInput = password(label = tr("Passwort"))

        lateinit var loginButton: io.kvision.html.Button
        loginButton =
            button(tr("Anmelden"), style = ButtonStyle.PRIMARY) {
                onClick {
                    val email = emailInput.value.orEmpty().trim()
                    val pw = passwordInput.value.orEmpty()
                    errorBox.hide()
                    if (!Validation.isNonBlank(email) || !Validation.isNonBlank(pw)) {
                        errorBox.content = tr("Bitte E-Mail und Passwort eingeben.")
                        errorBox.show()
                        return@onClick
                    }
                    loginButton.disabled = true
                    AppScope.launch {
                        val loginError = AuthHttp.login(email, pw)
                        if (loginError != null) {
                            errorBox.content = loginError
                            errorBox.show()
                            loginButton.disabled = false
                            return@launch
                        }
                        val session = guarded { rpcService<IAuthService>().getSessionInfo() }
                        loginButton.disabled = false
                        if (session != null) {
                            AppState.setSession(session)
                            notifySuccess(gettext("Willkommen, %1.", session.displayName))
                            navigateTo(Routes.DASHBOARD)
                        } else {
                            errorBox.content =
                                tr("Anmeldung erfolgreich, aber Sitzungsdaten konnten nicht geladen werden.")
                            errorBox.show()
                        }
                    }
                }
            }

        div {
            marginTop = 8.px
            link(tr("Noch kein Konto? Jetzt Mitglied werden."), url = "#${Routes.REGISTER}")
        }

        // V0.11.0 FRIEND self-registration -- third entry link, for a caller who wants NOTHING but
        // video-conference access (no Beitritt, no board approval). Deliberately its own paragraph,
        // not merged into the REGISTER link above, so the copy can be honest about the narrower
        // scope right where the choice is made.
        div {
            marginTop = 8.px
            link(tr("Nur an einer Videokonferenz teilnehmen? Freund-Konto anlegen."), url = "#${Routes.REGISTER_FRIEND}")
        }

        // V0.8.2 OIDC-Gastzugang-Federation: a plain, full-page-navigation link (NOT an SPA hash
        // route) to the server-rendered "log in with your home server" entry point -- see
        // `network.lapis.cloud.server.routes.OidcRoutes` KDoc. This is the only UI touch-point this
        // wave makes; the guest timeline badge is a separate wave (V0.8.4).
        div {
            marginTop = 8.px
            link(
                tr("Gast eines anderen Lapis-Cloud-Servers? Mit Heimatserver anmelden."),
                url = "/federation/oidc/rp/login",
                // Echte volle Seitennavigation, keine SPA-Hash-Route -- ohne dieses Opt-out fängt
                // das globale `Link.useDataNavigoForLinks = true` (App.kt main()) den Klick ab und
                // navigo behandelt den Pfad faelschlich als unbekannte Client-Route (notFound).
                dataNavigo = false,
            )
        }

        renderForgotPasswordToggle(this)
    }
}

/** Minimal request+confirm "forgot password" flow -- see V0.7.3 plan Open Question 3. Collapsed
 * behind a toggle link so it doesn't crowd the primary login form. */
private fun renderForgotPasswordToggle(parent: SimplePanel) {
    // dataNavigo = false: kein echter Routen-Link, nur ein lokaler Panel-Toggle -- ohne dieses
    // Opt-out feuert navigo (globales Link.useDataNavigoForLinks = true, siehe App.kt main()) auf
    // demselben Klick zusaetzlich seinen eigenen notFound-Handler und navigiert die ganze Seite neu,
    // was das gerade geoeffnete Panel im selben Tick wieder verwirft.
    val toggleLink = parent.link(tr("Passwort vergessen?"), url = "javascript:void(0)", dataNavigo = false)
    val panel = parent.vPanel(spacing = 6) { hide() }
    toggleLink.onClick { if (panel.visible) panel.hide() else panel.show() }

    panel.p(
        tr(
            "Geben Sie Ihre E-Mail-Adresse ein, um einen Link zum Zurücksetzen anzufordern. " +
                "Erhalten Sie eine Bestätigung, tragen Sie anschließend den Token und Ihr neues Passwort ein.",
        ),
    )
    val resetEmail = panel.text(type = InputType.EMAIL, label = tr("E-Mail"))
    val requestButton = panel.button(tr("Zurücksetzen anfordern"), style = ButtonStyle.OUTLINEPRIMARY)
    requestButton.onClick {
        val email = resetEmail.value.orEmpty().trim()
        if (!Validation.isNonBlank(email)) return@onClick
        AppScope.launch {
            val error = AuthHttp.requestPasswordReset(email)
            if (error != null) {
                notifyError(error)
            } else {
                notifyInfo(tr("Falls diese E-Mail registriert ist, wurde ein Link versendet."))
            }
        }
    }

    panel.div { marginTop = 8.px }
    val resetToken = panel.text(label = tr("Token (aus der E-Mail bzw. vom Betreiber)"))
    val newPassword = panel.password(label = tr("Neues Passwort"))
    val confirmButton = panel.button(tr("Neues Passwort setzen"), style = ButtonStyle.OUTLINEPRIMARY)
    confirmButton.onClick {
        val token = resetToken.value.orEmpty().trim()
        val pw = newPassword.value.orEmpty()
        if (!Validation.isNonBlank(token) || !Validation.isNonBlank(pw)) return@onClick
        AppScope.launch {
            val error = AuthHttp.confirmPasswordReset(token, pw)
            if (error != null) {
                notifyError(error)
            } else {
                notifySuccess(tr("Passwort wurde geändert -- bitte melden Sie sich neu an."))
                panel.hide()
            }
        }
    }
}
