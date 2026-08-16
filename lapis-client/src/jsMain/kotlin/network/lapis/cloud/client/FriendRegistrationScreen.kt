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
import network.lapis.cloud.shared.domain.FriendRegistrationInput
import network.lapis.cloud.shared.domain.FriendTermsDto
import network.lapis.cloud.shared.rpc.IRegistrationService

/**
 * V0.11.0 -- self-service FRIEND registration. Clone of [renderRegistrationScreen]'s shape, but for
 * a smaller, non-membership legal act: the registrant must see and explicitly accept the CURRENT,
 * versioned+hashed FRIEND terms (`getFriendTerms()`) before `registerFriend(...)` is even enabled.
 * Unlike [renderRegistrationScreen], the resulting account is **usable immediately** -- there is no
 * board-approval step -- so this screen's pending panel says so explicitly, never "wird vom Vorstand
 * geprüft". The server response is `Unit` unconditionally, including for a duplicate email (see
 * [IRegistrationService.registerFriend] KDoc "account-enumeration hardening"), so this screen shows
 * the IDENTICAL confirmation state either way and must not try to distinguish the two cases.
 */
fun renderFriendRegistrationScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 10) {
            addCssClass("mx-auto")
            width = 480.px
            marginTop = 32.px
        }
    root.h1(tr("Freund-Konto anlegen"))
    root.p(
        tr(
            "Ein Freund-Konto ist keine Mitgliedschaft -- es dient ausschließlich der Teilnahme an " +
                "Videokonferenzen, zu denen Sie eingeladen wurden.",
        ),
    )
    val loadingNotice = root.p(tr("Nutzungsbedingungen werden geladen ..."))

    AppScope.launch {
        val terms = guarded { rpcService<IRegistrationService>().getFriendTerms() }
        loadingNotice.hide()
        if (terms != null) renderFriendRegistrationForm(root, terms)
    }
}

private fun renderFriendRegistrationForm(
    root: SimplePanel,
    terms: FriendTermsDto,
) {
    root.h2(gettext("Nutzungsbedingungen fuer Freund-Konten (Version %1)", terms.version))
    root.div {
        addCssClasses("border rounded p-2 mb-2")
        maxHeight = 240.px
        overflow = Overflow.AUTO
        content = terms.text
    }

    val displayNameInput = root.text(label = tr("Anzeigename (nicht überprüft)"))
    val emailInput = root.text(type = InputType.EMAIL, label = tr("E-Mail"))
    val passwordInput =
        root.password(label = gettext("Passwort (mind. %1 Zeichen)", Validation.PASSWORD_MIN_LENGTH))
    val confirmPasswordInput = root.password(label = tr("Passwort bestätigen"))
    val agreeCheck = root.checkBox(label = tr("Ich habe die Nutzungsbedingungen für Freund-Konten gelesen und akzeptiere sie."))

    val errorBox =
        root.div().apply {
            addCssClass("text-danger")
            hide()
        }

    lateinit var submitButton: Button
    submitButton =
        root.button(tr("Freund-Konto anlegen"), style = ButtonStyle.PRIMARY) {
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
                    errorBox.content = tr("Bitte bestätigen Sie, dass Sie die Nutzungsbedingungen gelesen haben.")
                    errorBox.show()
                    return@onClick
                }

                submitButton.disabled = true
                AppScope.launch {
                    val result =
                        guarded {
                            rpcService<IRegistrationService>().registerFriend(
                                FriendRegistrationInput(
                                    displayName = displayName,
                                    email = email,
                                    password = password,
                                    termsVersion = terms.version,
                                    termsSha256 = terms.sha256,
                                ),
                            )
                        }
                    submitButton.disabled = false
                    if (result != null) {
                        root.removeAll()
                        renderFriendRegistrationConfirmation(root)
                    }
                }
            }
        }

    root.div {
        marginTop = 8.px
        link(tr("Doch lieber Mitglied werden? Zum Beitrittsantrag."), url = "#${Routes.REGISTER}")
    }
    root.div {
        link(tr("Bereits ein Konto? Zur Anmeldung."), url = "#${Routes.LOGIN}")
    }
}

private fun renderFriendRegistrationConfirmation(root: SimplePanel) {
    root.h1(tr("Freund-Konto angelegt"))
    root.p(
        tr(
            "Ihr Freund-Konto ist sofort nutzbar -- Sie können sich jetzt mit Ihrem gewählten Passwort " +
                "anmelden und an Videokonferenzen teilnehmen, zu denen Sie eingeladen wurden.",
        ),
    )
    root.link(tr("Zur Anmeldung"), url = "#${Routes.LOGIN}")
}
