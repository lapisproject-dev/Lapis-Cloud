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
    root.h1("Willkommen, ${session.displayName}")
    root.p("Rolle: ${session.role} · Sitzung gültig bis ${session.expiresAt}")

    root.h2("Bereiche")
    val nav = root.vPanel(spacing = 6)
    navTile(nav, "Beitragsübersicht", Routes.CONTRIBUTIONS)
    navTile(nav, "Dokumentenablage", Routes.DOCUMENTS)
    navTile(nav, "Kommunikation", Routes.COMMUNICATION)
    navTile(nav, "Gremien", Routes.COMMITTEES)
    navTile(nav, "Sitzungen", Routes.MEETINGS)
    navTile(nav, "Anträge", Routes.MOTIONS)
    // Accounting UI wave, design decision D15: same placement/role-gating as the navbar link --
    // see `App.kt` `refreshNavbar`.
    if (AppState.hasRole(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)) {
        navTile(nav, "Kontenplan & Journal", Routes.LEDGER)
        navTile(nav, "Finanzberichte", Routes.FINANCIAL_REPORTS)
        navTile(nav, "Gemeinnützigkeits-Berichte", Routes.COMPLIANCE_REPORTS)
        navTile(nav, "Kostenstellen", Routes.COST_CENTERS)
        navTile(nav, "Spender", Routes.DONORS)
    }
    if (AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)) {
        navTile(nav, "Mitgliederverwaltung", Routes.MEMBERS)
    }

    root.h2("Konto")
    renderChangePassword(root)
    renderAccountActions(root)
}

private fun navTile(
    parent: SimplePanel,
    label: String,
    route: String,
) {
    parent.link(label, url = "#$route") {
        addCssClasses("btn btn-outline-primary text-start")
    }
}

private fun renderChangePassword(root: SimplePanel) {
    val panel = root.vPanel(spacing = 6)
    panel.p("Passwort ändern")
    val currentPasswordInput = panel.password(label = "Aktuelles Passwort")
    val newPasswordInput = panel.password(label = "Neues Passwort (mind. ${Validation.PASSWORD_MIN_LENGTH} Zeichen)")
    val confirmPasswordInput = panel.password(label = "Neues Passwort bestätigen")
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }

    val changeButton: Button = panel.button("Passwort ändern", style = ButtonStyle.OUTLINESECONDARY)
    changeButton.onClick {
        errorBox.hide()
        val currentPassword = currentPasswordInput.value.orEmpty()
        val newPassword = newPasswordInput.value.orEmpty()
        val confirmPassword = confirmPasswordInput.value.orEmpty()

        if (!Validation.isNonBlank(currentPassword) || !Validation.isNonBlank(newPassword)) {
            errorBox.content = "Bitte alle Felder ausfüllen."
            errorBox.show()
            return@onClick
        }
        if (!Validation.passwordsMatch(newPassword, confirmPassword)) {
            errorBox.content = "Die neuen Passwörter stimmen nicht überein."
            errorBox.show()
            return@onClick
        }

        changeButton.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IAuthService>().changePassword(currentPassword, newPassword) }
            changeButton.disabled = false
            if (result != null) {
                notifySuccess("Passwort geändert.")
                currentPasswordInput.value = null
                newPasswordInput.value = null
                confirmPasswordInput.value = null
            }
        }
    }
}

private fun renderAccountActions(root: SimplePanel) {
    val actionRow = root.hPanel(spacing = 8)

    val logoutButton = actionRow.button("Abmelden", style = ButtonStyle.SECONDARY)
    logoutButton.onClick {
        AppScope.launch {
            AuthHttp.logout()
            AppState.setSession(null)
            navigateTo(Routes.LOGIN)
        }
    }

    val exitButton = actionRow.button("Austritt (Mitgliedschaft beenden)", style = ButtonStyle.OUTLINEDANGER)
    exitButton.onClick {
        confirmDialog(
            title = "Austritt bestätigen",
            message =
                "Möchten Sie Ihre Mitgliedschaft wirklich beenden? Dies ist nicht rückgängig zu machen -- " +
                    "Sie werden abgemeldet und können sich nicht erneut mit diesem Konto anmelden.",
            confirmLabel = "Austritt bestätigen",
        ) {
            AppScope.launch {
                val result = guarded { rpcService<IRegistrationService>().leaveMembership() }
                if (result != null) {
                    AppState.setSession(null)
                    notifyInfo("Sie sind ausgetreten.")
                    navigateTo(Routes.LOGIN)
                }
            }
        }
    }
}
