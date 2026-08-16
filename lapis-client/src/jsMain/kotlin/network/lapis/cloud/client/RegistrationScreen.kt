package network.lapis.cloud.client

import io.kvision.core.Overflow
import io.kvision.form.check.checkBox
import io.kvision.form.text.password
import io.kvision.form.text.text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.MembershipAgreementDto
import network.lapis.cloud.shared.domain.RegistrationInput
import network.lapis.cloud.shared.rpc.IRegistrationService

/**
 * Screen 2 of the V0.7.3 plan -- self-service join flow. The registrant must see and explicitly
 * accept the CURRENT, versioned+hashed Beitrittsvertrag text (`getMembershipAgreement()`) before
 * `registerApplication(...)` is even enabled; this is a real legal-acknowledgment step ("Membership
 * is a private-law contract", see `IRegistrationService` KDoc), not decorative. On success the
 * applicant is `APPLICATION` (pending board approval) -- this screen ends on a clear "application
 * pending" notice, never a dashboard, and never auto-logs in (the server response is `Unit`
 * unconditionally, including for a duplicate email -- see [IRegistrationService.registerApplication]
 * KDoc "account-enumeration hardening" -- so this screen shows the IDENTICAL pending state either
 * way and must not try to distinguish the two cases).
 */
fun renderRegistrationScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 10) {
            addCssClass("mx-auto")
            width = 480.px
            marginTop = 32.px
        }
    root.h1(tr("Mitglied werden"))
    val loadingNotice = root.p(tr("Beitrittsvertrag wird geladen ..."))

    AppScope.launch {
        val agreement = guarded { rpcService<IRegistrationService>().getMembershipAgreement() }
        loadingNotice.hide()
        if (agreement != null) renderRegistrationForm(root, agreement)
    }
}

private fun renderRegistrationForm(
    root: SimplePanel,
    agreement: MembershipAgreementDto,
) {
    root.h2(gettext("Beitrittsvertrag (Version %1)", agreement.version))
    root.div {
        addCssClasses("border rounded p-2 mb-2")
        maxHeight = 240.px
        overflow = Overflow.AUTO
        content = agreement.text
    }

    val displayNameInput = root.text(label = tr("Name"))
    val emailInput = root.text(type = InputType.EMAIL, label = tr("E-Mail"))
    val passwordInput =
        root.password(label = gettext("Passwort (mind. %1 Zeichen)", Validation.PASSWORD_MIN_LENGTH))
    val confirmPasswordInput = root.password(label = tr("Passwort bestätigen"))
    val agreeCheck = root.checkBox(label = tr("Ich habe den Beitrittsvertrag gelesen und akzeptiere ihn."))

    val errorBox =
        root.div().apply {
            addCssClass("text-danger")
            hide()
        }

    lateinit var submitButton: Button
    submitButton =
        root.button(tr("Antrag einreichen"), style = ButtonStyle.PRIMARY) {
            onClick {
                errorBox.hide()
                val displayName = displayNameInput.value.orEmpty().trim()
                val email = emailInput.value.orEmpty().trim()
                val password = passwordInput.value.orEmpty()
                val confirmPassword = confirmPasswordInput.value.orEmpty()

                if (!Validation.isNonBlank(displayName) || !Validation.looksLikeEmail(email)) {
                    errorBox.content = tr("Bitte Name und eine gültige E-Mail-Adresse angeben.")
                    errorBox.show()
                    return@onClick
                }
                val passwordHint = Validation.passwordHint(password, email)
                if (passwordHint != null) {
                    errorBox.content = passwordHint
                    errorBox.show()
                    return@onClick
                }
                if (!Validation.passwordsMatch(password, confirmPassword)) {
                    errorBox.content = tr("Die Passwörter stimmen nicht überein.")
                    errorBox.show()
                    return@onClick
                }
                if (!agreeCheck.value) {
                    errorBox.content = tr("Bitte bestätigen Sie, dass Sie den Beitrittsvertrag gelesen haben.")
                    errorBox.show()
                    return@onClick
                }

                submitButton.disabled = true
                AppScope.launch {
                    val result =
                        guarded {
                            rpcService<IRegistrationService>().registerApplication(
                                RegistrationInput(
                                    displayName = displayName,
                                    email = email,
                                    password = password,
                                    agreementVersion = agreement.version,
                                    agreementSha256 = agreement.sha256,
                                ),
                            )
                        }
                    submitButton.disabled = false
                    if (result != null) {
                        root.removeAll()
                        renderRegistrationPending(root)
                    }
                }
            }
        }

    root.div {
        marginTop = 8.px
        link(tr("Bereits Mitglied? Zur Anmeldung."), url = "#${Routes.LOGIN}")
    }
}

private fun renderRegistrationPending(root: SimplePanel) {
    root.h1(tr("Antrag eingereicht"))
    root.p(
        tr(
            "Ihr Mitgliedschaftsantrag wurde eingereicht und wird vom Vorstand geprüft. " +
                "Sie sind noch nicht angemeldet -- nach der Freigabe können Sie sich mit Ihrem gewählten " +
                "Passwort anmelden.",
        ),
    )
    root.link(tr("Zur Anmeldung"), url = "#${Routes.LOGIN}")
}
