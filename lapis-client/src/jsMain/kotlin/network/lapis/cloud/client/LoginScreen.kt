package network.lapis.cloud.client

import io.kvision.html.Autocomplete
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.div
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
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
        maxWidth = AUTH_CARD_MAX_WIDTH_PX.px
        width = 100.perc
        marginTop = 64.px

        // V1.4.7 "Root-Verlinkung": das Marken-Lockup ersetzt hier `h1(Branding.title)` -- der
        // Screen behält dafür ein eigenes, screenspezifisches h1 (Design-Team-Review V1.4.7).
        brandLockup()
        pageHeader(tr("Anmelden"))

        // V1.7.2 sub-wave 2b "Keycloak als externe Benutzerverwaltung -- UI": read BEFORE any RPC
        // round-trip, same as `Branding.title`/`logoUrl` -- see `Branding.keycloakMode` KDoc.
        if (Branding.keycloakMode) {
            renderKeycloakLoginPanel(this)
        } else {
            p(tr("Bitte melden Sie sich mit Ihrer E-Mail-Adresse an."))
            renderEmailPasswordLoginForm(this)
            renderForgotPasswordToggle(this)
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

        // Welle V1.4.6 "Öffentliche Startseite" -- ein kleiner Rücklink zur neuen, server-
        // gerenderten Landingpage unter "/". Echte volle Seitennavigation (kein Hash-Routen-Ziel),
        // dasselbe dataNavigo = false-Opt-out wie beim OIDC-Federation-Link oben -- ohne dieses
        // Opt-out fängt das globale Link.useDataNavigoForLinks (App.kt main()) den Klick ab.
        div {
            marginTop = 8.px
            link(tr("Zur öffentlichen Startseite"), url = "/", dataNavigo = false)
        }
    }
}

/**
 * The classic email/password form -- extracted (V1.7.2 sub-wave 2b) so the SAME form can be
 * mounted either as the screen's primary content (non-Keycloak deployments) or collapsed behind
 * [renderEmergencyAdminLoginToggle]'s disclosure (Keycloak mode, vault spec decision 3). Behaviour
 * is byte-for-byte what [renderLoginScreen] always did: `POST /api/auth/login`, then
 * `getSessionInfo()` on success (see this file's class KDoc).
 */
private fun renderEmailPasswordLoginForm(parent: SimplePanel) {
    val form = parent.lapisForm()
    // Zwei Felder, beide Pflicht: weder Stern noch Legende -- `aria-required` steht trotzdem an beiden.
    val emailField =
        form.textField(
            label = tr("E-Mail"),
            type = InputType.EMAIL,
            required = true,
            autocomplete = Autocomplete.USERNAME,
        )
    // Regel NUR "nicht leer" (required): `Validation.passwordHint` wird hier nie aufgerufen -- ein altes, damals gültiges
    // Passwort darf die Oberfläche nicht für falsch erklären.
    val passwordField =
        form.passwordField(
            label = tr("Passwort"),
            required = true,
            autocomplete = Autocomplete.CURRENT_PASSWORD,
        )
    val loginButton = Button(tr("Anmelden"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = loginButton)
    loginButton.onClick {
        form.submit(loginButton) {
            val email = emailField.value.trim()
            val pw = passwordField.value
            val loginError = AuthHttp.login(email, pw)
            if (loginError != null) {
                // Der Servertext wörtlich: er ist bereits gegen Kontoaufzählung gehärtet (siehe KDoc oben).
                form.showFormError(loginError)
                return@submit
            }
            val session = guarded { rpcService<IAuthService>().getSessionInfo() }
            if (session != null) {
                AppState.setSession(session)
                notifySuccess(gettext("Willkommen, %1.", session.displayName))
                navigateTo(Routes.DASHBOARD)
            } else {
                form.showFormError(tr("Anmeldung erfolgreich, aber Sitzungsdaten konnten nicht geladen werden."))
            }
        }
    }
}

/**
 * V1.7.2 sub-wave 2b "Keycloak als externe Benutzerverwaltung -- UI" -- the Keycloak-mode branch of
 * the login screen ([Branding.keycloakMode] `== true`). Replaces the email/password form with a
 * single, clearly-labeled button doing a FULL PAGE navigation (not an SPA/navigo-intercepted link)
 * to `/auth/keycloak/start` (the server-side OIDC-RP entry point of Wave 1) -- same `dataNavigo =
 * false` opt-out the federation login link below already establishes, and for the same reason: KVision's
 * global `Link.useDataNavigoForLinks = true` (`App.kt` `main()`) would otherwise intercept the click and
 * navigo would treat the path as an unknown client route (`notFound`). Styled as a real button
 * (`btn btn-primary`) via `addCssClasses` -- it is still an `<a>` under the hood, which is exactly what
 * makes the `dataNavigo = false` opt-out apply.
 *
 * Keeps [renderEmergencyAdminLoginToggle]'s small, collapsed-by-default disclosure with the classic
 * email/password form underneath (vault spec decision 3, "Notfall-Login für Admins bleibt bestehen") --
 * reachable, but not the prominent default UI an ordinary member sees. No "Passwort vergessen?" toggle
 * here: password reset is a 404 server-side in Keycloak mode (Wave 1 `AuthRoutes`/`PasswordResetService`),
 * so offering the link would be a dead end.
 */
private fun renderKeycloakLoginPanel(parent: SimplePanel) {
    parent.p(tr("Bitte melden Sie sich über Ihre Organisation an."))
    parent.link(
        gettext("Mit %1 anmelden", Branding.title),
        url = "/auth/keycloak/start",
        dataNavigo = false,
    ) {
        addCssClasses("btn btn-primary")
    }
    // Review fix (MINOR 3): rendering this unconditionally made the form a dead end whenever this
    // deployment has `KeycloakConfig.emergencyAdminLoginEnabled == false` -- every local login is
    // then rejected server-side, indistinguishable in the UI from a wrong-password error. See
    // `Branding.emergencyAdminLoginEnabled` KDoc.
    if (Branding.emergencyAdminLoginEnabled) {
        renderEmergencyAdminLoginToggle(parent)
    }
}

/**
 * The emergency ADMIN path of Keycloak mode (vault spec decision 3): a discreet, collapsed-by-default
 * disclosure that reveals the classic email/password form on click -- same toggle mechanics as
 * [renderForgotPasswordToggle] (`dataNavigo = false` local panel toggle, not a routed link). Deliberately
 * NOT offered to ordinary members as a prominent choice: only an ADMIN account can actually use it (a
 * non-ADMIN local password login is rejected server-side in Keycloak mode, see `AuthService
 * .changePassword` KDoc "keycloakConfig" for the mirrored server-side gate on the self-service path --
 * the LOGIN route itself has no role to check against before authentication, so this stays a UI-level
 * discretion, not an enforcement boundary).
 */
private fun renderEmergencyAdminLoginToggle(parent: SimplePanel) {
    val toggleLink = parent.link(tr("Interner Notfall-Zugang für Administratoren"), url = "javascript:void(0)", dataNavigo = false)
    val panel = parent.vPanel(spacing = 10) { hide() }
    toggleLink.onClick { if (panel.visible) panel.hide() else panel.show() }
    renderEmailPasswordLoginForm(panel)
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
    val requestForm = panel.lapisForm()
    val resetEmail =
        requestForm.textField(
            label = tr("E-Mail"),
            type = InputType.EMAIL,
            required = true,
            autocomplete = Autocomplete.USERNAME,
        )
    val requestButton = Button(tr("Zurücksetzen anfordern"), style = ButtonStyle.OUTLINEPRIMARY)
    requestForm.buttons(primary = requestButton)
    requestButton.onClick {
        requestForm.submit(requestButton) {
            val error = AuthHttp.requestPasswordReset(resetEmail.value.trim())
            if (error != null) {
                requestForm.showFormError(error)
            } else {
                notifyInfo(tr("Falls diese E-Mail registriert ist, wurde ein Link versendet."))
            }
        }
    }

    panel.div { marginTop = 8.px }
    val confirmForm = panel.lapisForm()
    val resetToken =
        confirmForm.textField(
            label = tr("Token (aus der E-Mail bzw. vom Betreiber)"),
            required = true,
            autocomplete = Autocomplete.ONE_TIME_CODE,
        )
    val newPassword =
        confirmForm.passwordField(
            label = tr("Neues Passwort"),
            required = true,
            autocomplete = Autocomplete.NEW_PASSWORD,
            hint = gettext("Mindestens %1 Zeichen.", Validation.PASSWORD_MIN_LENGTH),
            rule = { FormRules.newPassword(value = it, email = "") },
        )
    val confirmButton = Button(tr("Neues Passwort setzen"), style = ButtonStyle.OUTLINEPRIMARY)
    confirmForm.buttons(primary = confirmButton)
    confirmButton.onClick {
        confirmForm.submit(confirmButton) {
            val error = AuthHttp.confirmPasswordReset(resetToken.value.trim(), newPassword.value)
            if (error != null) {
                confirmForm.showFormError(error)
            } else {
                notifySuccess(tr("Passwort wurde geändert -- bitte melden Sie sich neu an."))
                panel.hide()
            }
        }
    }
}
