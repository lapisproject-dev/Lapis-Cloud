package network.lapis.cloud.client

import io.kvision.html.Autocomplete
import io.kvision.html.ButtonStyle
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
import io.kvision.utils.px
import kotlinx.browser.window

/**
 * Welle V1.9.56 "E-Mail-Änderung absichern" -- the three destinations of the links in the address-change mails:
 * `#/confirm-email?token=...` (accept a proposal with the password), `#/verify-new-email?token=...` (prove that the new
 * address is yours) and `#/revoke-email-change?token=...` (reject, from the mail to the OLD address).
 *
 * Differences to [renderVerifyEmailScreen], on purpose:
 *  - **Nothing is sent on load.** Mail clients and link scanners open links; only a click on the button sends the POST.
 *  - **The token leaves the URL at once** ([stripTokenFromUrl]): it lives in a local variable from then on, so a reload,
 *    a bookmark, a shared screen or the browser history no longer carry a bearer secret.
 *  - **No server text is ever shown.** Only the closed [EmailChangeLinkOutcome] set is evaluated, every sentence is fixed
 *    here (wrong, used, expired and foreign tokens are all "ungültig oder abgelaufen" -- the server says the same).
 *  - After a success there is a link to the sign-in page, never an automatic redirect to a target taken from anywhere.
 */
private enum class EmailChangeLinkKind { CONFIRM_WITH_PASSWORD, VERIFY_NEW_ADDRESS, REVOKE }

fun renderConfirmEmailScreen(
    container: SimplePanel,
    token: String?,
) = renderEmailChangeLinkScreen(container, token, EmailChangeLinkKind.CONFIRM_WITH_PASSWORD, Routes.CONFIRM_EMAIL)

fun renderVerifyNewEmailScreen(
    container: SimplePanel,
    token: String?,
) = renderEmailChangeLinkScreen(container, token, EmailChangeLinkKind.VERIFY_NEW_ADDRESS, Routes.VERIFY_NEW_EMAIL)

fun renderRevokeEmailChangeScreen(
    container: SimplePanel,
    token: String?,
) = renderEmailChangeLinkScreen(container, token, EmailChangeLinkKind.REVOKE, Routes.REVOKE_EMAIL_CHANGE)

/** The fixed sentence of a failed link action -- one place, so the three screens can never drift. */
internal fun emailChangeLinkFailureText(outcome: EmailChangeLinkOutcome): String =
    when (outcome) {
        EmailChangeLinkOutcome.OK, EmailChangeLinkOutcome.CONFIRMED_PENDING -> ""
        EmailChangeLinkOutcome.INVALID -> tr("Dieser Link ist ungültig oder abgelaufen.")
        EmailChangeLinkOutcome.WRONG_PASSWORD -> tr("Das Passwort ist nicht korrekt.")
        EmailChangeLinkOutcome.UNAVAILABLE -> tr("Diese E-Mail-Adresse ist inzwischen nicht mehr verfügbar.")
        EmailChangeLinkOutcome.RATE_LIMITED -> tr("Zu viele Versuche. Bitte versuchen Sie es später erneut.")
        EmailChangeLinkOutcome.FAILED -> tr("Die Anfrage ist fehlgeschlagen. Bitte versuchen Sie es später erneut.")
    }

/** Replaces the current hash (`#/route?token=...`) by the bare route -- `replaceState` fires neither `hashchange` nor `popstate`. */
internal fun stripTokenFromUrl(route: String) {
    window.history.replaceState(null, "", "#$route")
}

private fun renderEmailChangeLinkScreen(
    container: SimplePanel,
    token: String?,
    kind: EmailChangeLinkKind,
    route: String,
) {
    val root =
        container.vPanel(spacing = 10) {
            addCssClass("mx-auto")
            maxWidth = AUTH_CARD_MAX_WIDTH_PX.px
            width = 100.perc
            marginTop = 64.px
        }
    root.brandLockup()
    root.pageHeader(
        when (kind) {
            EmailChangeLinkKind.CONFIRM_WITH_PASSWORD -> tr("Neue Adresse für Ihr Konto übernehmen?")
            EmailChangeLinkKind.VERIFY_NEW_ADDRESS -> tr("Diese Adresse bestätigen?")
            EmailChangeLinkKind.REVOKE -> tr("Änderung ablehnen?")
        },
    )

    if (token.isNullOrBlank()) {
        root.p(tr("Dieser Link ist ungültig oder abgelaufen."))
        root.link(tr("Zur Anmeldung"), url = "#${Routes.LOGIN}")
        return
    }
    // From here on the token exists only in this closure.
    stripTokenFromUrl(route)

    root.p(
        when (kind) {
            EmailChangeLinkKind.CONFIRM_WITH_PASSWORD ->
                tr(
                    "Für Ihr Konto wurde eine neue Anmeldeadresse vorgeschlagen. Zur Bestätigung geben Sie bitte Ihr Passwort ein. " +
                        "Erst danach wird die Adresse geändert.",
                )
            EmailChangeLinkKind.VERIFY_NEW_ADDRESS ->
                tr(
                    "Bitte bestätigen Sie, dass diese E-Mail-Adresse Ihnen gehört. Die Änderung wird nach Ablauf der " +
                        "Warnfrist von 72 Stunden wirksam; bis dahin kann sie über die bisherige Adresse abgelehnt werden.",
                )
            EmailChangeLinkKind.REVOKE ->
                tr(
                    "Für Ihr Konto wurde eine Änderung der Anmeldeadresse beantragt. Wenn Sie das nicht erwartet haben, " +
                        "lehnen Sie die Änderung hier ab.",
                )
        },
    )

    val form = root.lapisForm()
    val password =
        if (kind == EmailChangeLinkKind.CONFIRM_WITH_PASSWORD) {
            form.passwordField(
                label = tr("Passwort"),
                required = true,
                autocomplete = Autocomplete.CURRENT_PASSWORD,
            )
        } else {
            null
        }
    val button =
        when (kind) {
            EmailChangeLinkKind.CONFIRM_WITH_PASSWORD ->
                newActionButton(
                    ActionIcon.APPROVE,
                    tr("Neue Adresse übernehmen"),
                    ButtonStyle.PRIMARY,
                )
            EmailChangeLinkKind.VERIFY_NEW_ADDRESS -> newActionButton(ActionIcon.APPROVE, tr("Adresse bestätigen"), ButtonStyle.PRIMARY)
            EmailChangeLinkKind.REVOKE -> newActionButton(ActionIcon.REJECT, tr("Änderung ablehnen"), ButtonStyle.OUTLINEDANGER)
        }
    if (kind == EmailChangeLinkKind.REVOKE) form.buttons(primary = null, destructive = button) else form.buttons(primary = button)

    button.onClick {
        form.submit(button) {
            val outcome =
                if (kind == EmailChangeLinkKind.REVOKE) {
                    AuthHttp.revokeEmailChange(token)
                } else {
                    AuthHttp.confirmEmailChange(token, password?.value)
                }
            if (outcome != EmailChangeLinkOutcome.OK && outcome != EmailChangeLinkOutcome.CONFIRMED_PENDING) {
                form.showFormError(emailChangeLinkFailureText(outcome))
            } else {
                form.clearFormError()
                button.disabled = true
                root.p(
                    when (kind) {
                        EmailChangeLinkKind.CONFIRM_WITH_PASSWORD ->
                            tr(
                                "Ihre neue Anmeldeadresse ist jetzt aktiv. Bitte melden Sie sich neu an.",
                            )
                        EmailChangeLinkKind.VERIFY_NEW_ADDRESS ->
                            if (outcome == EmailChangeLinkOutcome.CONFIRMED_PENDING) {
                                tr("Danke, die Adresse ist bestätigt. Die Änderung wird nach Ablauf der Warnfrist wirksam.")
                            } else {
                                tr("Danke, die Adresse ist bestätigt und die Änderung ist bereits wirksam. Bitte melden Sie sich neu an.")
                            }
                        EmailChangeLinkKind.REVOKE -> tr("Die Änderung wurde abgelehnt. Ihre bisherige Adresse bleibt gültig.")
                    },
                )
                root.link(tr("Zur Anmeldung"), url = "#${Routes.LOGIN}")
                notifySuccess(
                    when (kind) {
                        EmailChangeLinkKind.CONFIRM_WITH_PASSWORD -> tr("Anmeldeadresse geändert.")
                        EmailChangeLinkKind.VERIFY_NEW_ADDRESS -> tr("Adresse bestätigt.")
                        EmailChangeLinkKind.REVOKE -> tr("Änderung abgelehnt.")
                    },
                )
            }
        }
    }
}
