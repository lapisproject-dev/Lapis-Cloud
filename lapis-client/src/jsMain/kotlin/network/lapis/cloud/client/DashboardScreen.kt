package network.lapis.cloud.client

import io.kvision.form.text.password
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.rpc.IAuthService
import network.lapis.cloud.shared.rpc.IRegistrationService

/**
 * Screen 3 of the V0.7.3 plan -- the real post-login landing page. Shows the logged-in member's
 * own info (from [AppState.session], populated by `IAuthService.getSessionInfo()`), navigation to
 * the other screens (Mitgliederverwaltung tile only rendered for BOARD/ADMIN -- "must not even be
 * reachable/rendered" for a plain MEMBER, per the plan), a working logout, a self-service
 * change-password action, and a self-service Austritt action gated behind a real confirmation
 * step (see [ConfirmDialog]) since it is destructive/irreversible from the member's perspective.
 */
fun renderDashboardScreen(container: SimplePanel) {
    val session = AppState.session
    if (session == null) {
        navigateTo(Routes.LOGIN)
        return
    }

    val root =
        container.vPanel(spacing = 14) {
            addCssClass("mx-auto")
            width = 640.px
            marginTop = 24.px
        }
    root.h1(gettext("Willkommen, %1", session.displayName))
    // Zeigt sowohl MemberStatus (Kontotyp -- z.B. "Freund" statt der reinen Berechtigungsstufe) als
    // auch AccountRole (Berechtigungsstufe): ein Freund-Konto hat technisch korrekt AccountRole.MEMBER
    // (dieselbe Basis-Stufe wie jedes einfache Konto), aber "Rolle: MEMBER" allein liest sich fuer ein
    // Freund-Konto wie eine volle Mitgliedschaft. session.status existiert bereits seit V0.11.0 (fuer
    // die Navigations-Steuerung), wurde hier aber nie mit angezeigt -- reiner Anzeige-Fix, kein neues
    // Feld noetig.
    root.p(
        gettext(
            "Status: %1 · Rolle: %2 · Sitzung gültig bis %3",
            memberStatusLabel(session.status),
            accountRoleLabel(session.role),
            session.expiresAt,
        ),
    )

    root.h2(tr("Bereiche"))
    val nav = root.vPanel(spacing = 6)
    navTile(nav, tr("Beitragsübersicht"), Routes.CONTRIBUTIONS)
    navTile(nav, tr("Dokumentenablage"), Routes.DOCUMENTS)
    navTile(nav, tr("Kommunikation"), Routes.COMMUNICATION)
    navTile(nav, tr("Gremien"), Routes.COMMITTEES)
    navTile(nav, tr("Sitzungen"), Routes.MEETINGS)
    navTile(nav, tr("Anträge"), Routes.MOTIONS)
    // LTR-Wirtschaft UI wave: same placement/role-gating as the navbar link -- see `App.kt`
    // `refreshNavbar` / `Routes.LTR_LEDGER` KDoc.
    navTile(nav, tr("LTR-Konto"), Routes.LTR_LEDGER)
    // Accounting UI wave, design decision D15: same placement/role-gating as the navbar link --
    // see `App.kt` `refreshNavbar`.
    if (AppState.hasRole(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)) {
        navTile(nav, tr("Kontenplan & Journal"), Routes.LEDGER)
        navTile(nav, tr("Finanzberichte"), Routes.FINANCIAL_REPORTS)
        navTile(nav, tr("Gemeinnützigkeits-Berichte"), Routes.COMPLIANCE_REPORTS)
        navTile(nav, tr("Kostenstellen"), Routes.COST_CENTERS)
        navTile(nav, tr("Spender"), Routes.DONORS)
        // Mail-merge/Postal-Dispatch UI wave: same TREASURER/BOARD/ADMIN tier/placement as the
        // navbar link -- see `Routes.POSTAL_MAIL` KDoc.
        navTile(nav, tr("Postversand"), Routes.POSTAL_MAIL)
    }
    if (AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)) {
        navTile(nav, tr("Mitgliederverwaltung"), Routes.MEMBERS)
    }

    root.h2(tr("Konto"))
    renderChangePassword(root)
    renderAccountActions(root)
}

/**
 * UI theme redesign wave (2026-08-20): a real Bootstrap card, replacing the previous plain
 * `btn btn-outline-primary` link stack -- see root CLAUDE.md "UI/UX-Design-Team" review. Bootstrap
 * ships no dedicated card JS component (it is pure markup/CSS convention, confirmed against the
 * KVision 9.6.0 source tree -- no `Card` class exists there either), so "real card" here means the
 * genuine `.card` > `.card-body` DOM shape rather than an ad hoc bordered `vPanel`. The whole card
 * stays clickable via Bootstrap's `.stretched-link` utility on the inner link -- `.card` itself is
 * `position: relative` by default in Bootstrap, exactly the positioned ancestor `.stretched-link`
 * needs, so no extra CSS is required here.
 */
private fun navTile(
    parent: SimplePanel,
    label: String,
    route: String,
) {
    val card = parent.div { addCssClasses("card") }
    val body = card.div { addCssClasses("card-body py-2 px-3") }
    body.link(label, url = "#$route") {
        addCssClasses("card-title stretched-link text-decoration-none fw-semibold mb-0 d-block")
    }
}

private fun renderChangePassword(root: SimplePanel) {
    val panel = root.vPanel(spacing = 6)
    panel.p(tr("Passwort ändern"))
    val currentPasswordInput = panel.password(label = tr("Aktuelles Passwort"))
    val newPasswordInput =
        panel.password(label = gettext("Neues Passwort (mind. %1 Zeichen)", Validation.PASSWORD_MIN_LENGTH))
    val confirmPasswordInput = panel.password(label = tr("Neues Passwort bestätigen"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val changeButton: Button = panel.button(tr("Passwort ändern"), style = ButtonStyle.OUTLINESECONDARY)
    changeButton.onClick {
        errorBox.hide()
        val currentPassword = currentPasswordInput.value.orEmpty()
        val newPassword = newPasswordInput.value.orEmpty()
        val confirmPassword = confirmPasswordInput.value.orEmpty()

        if (!Validation.isNonBlank(currentPassword) || !Validation.isNonBlank(newPassword)) {
            errorBox.content = tr("Bitte alle Felder ausfüllen.")
            errorBox.show()
            return@onClick
        }
        if (!Validation.passwordsMatch(newPassword, confirmPassword)) {
            errorBox.content = tr("Die neuen Passwörter stimmen nicht überein.")
            errorBox.show()
            return@onClick
        }

        changeButton.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IAuthService>().changePassword(currentPassword, newPassword) }
            changeButton.disabled = false
            if (result != null) {
                notifySuccess(tr("Passwort geändert."))
                currentPasswordInput.value = null
                newPasswordInput.value = null
                confirmPasswordInput.value = null
            }
        }
    }
}

private fun renderAccountActions(root: SimplePanel) {
    val actionRow = root.hPanel(spacing = 8)

    val logoutButton = actionRow.button(tr("Abmelden"), style = ButtonStyle.SECONDARY)
    logoutButton.onClick {
        AppScope.launch {
            AuthHttp.logout()
            AppState.setSession(null)
            navigateTo(Routes.LOGIN)
        }
    }

    val exitButton = actionRow.button(tr("Austritt (Mitgliedschaft beenden)"), style = ButtonStyle.OUTLINEDANGER)
    exitButton.onClick {
        confirmDialog(
            title = tr("Austritt bestätigen"),
            message =
                tr(
                    "Möchten Sie Ihre Mitgliedschaft wirklich beenden? Dies ist nicht rückgängig zu machen -- " +
                        "Sie werden abgemeldet und können sich nicht erneut mit diesem Konto anmelden.",
                ),
            confirmLabel = tr("Austritt bestätigen"),
        ) {
            AppScope.launch {
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
