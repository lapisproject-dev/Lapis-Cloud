package network.lapis.cloud.client

import io.kvision.core.Overflow
import io.kvision.html.Autocomplete
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import io.kvision.utils.perc
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
            maxWidth = NARROW_FORM_MAX_WIDTH_PX.px
            width = 100.perc
            marginTop = 32.px
        }
    // V1.4.7 "Root-Verlinkung" -- Marken-Lockup über der Karte, siehe LoginScreen.kt für dasselbe
    // Muster. root.h1 bleibt unverändert der screenspezifische Titel.
    root.brandLockup()
    root.pageHeader(tr("Freund-Konto anlegen"))
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
    root.h2(gettext("Nutzungsbedingungen fuer Freund-Konten (Version %1)", terms.version)) { addCssClass("h5") }
    root.div {
        addCssClasses("border rounded p-2 mb-2")
        maxHeight = 240.px
        overflow = Overflow.AUTO
        content = terms.text
    }

    val form = root.lapisForm()
    // Vier Felder, alle Pflicht: keine Sterne, Legende "Alle Felder sind Pflichtfelder." (Fall b).
    val displayNameField = form.textField(label = tr("Anzeigename (nicht überprüft)"), required = true)
    val emailField =
        form.textField(
            label = tr("E-Mail"),
            type = InputType.EMAIL,
            required = true,
            autocomplete = Autocomplete.USERNAME,
            rule = FormRules::email,
        )
    // Die Passwortregel steht als Hinweis UNTER dem Feld (sichtbar, bevor getippt wird), nicht im Label.
    val passwordField =
        form.passwordField(
            label = tr("Passwort"),
            required = true,
            autocomplete = Autocomplete.NEW_PASSWORD,
            hint = gettext("Mindestens %1 Zeichen.", Validation.PASSWORD_MIN_LENGTH),
            rule = { FormRules.newPassword(value = it, email = emailField.value.trim()) },
        )
    val confirmPasswordField =
        form.passwordField(
            label = tr("Passwort bestätigen"),
            required = true,
            autocomplete = Autocomplete.NEW_PASSWORD,
        )
    form.crossFieldRule(field = confirmPasswordField) {
        FormRules.passwordsMatch(password = passwordField.value, confirmation = confirmPasswordField.value)
    }
    // Die Zustimmung ist ein Feld der Grammatik (V1.4.29, `checkField`): Pflicht heißt "angekreuzt", der Fehler steht AM Feld
    // (nicht mehr in der Sammelfläche), `aria-required` setzt der Baustein.
    form.checkField(
        label = tr("Ich habe die Nutzungsbedingungen für Freund-Konten gelesen und akzeptiere sie."),
        required = true,
        requiredMessage = gettext("Bitte bestätigen Sie, dass Sie die Nutzungsbedingungen gelesen haben."),
    )

    val submitButton = Button(tr("Freund-Konto anlegen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = submitButton)
    submitButton.onClick {
        form.submit(submitButton) {
            val result =
                guarded {
                    rpcService<IRegistrationService>().registerFriend(
                        buildFriendRegistrationInput(
                            displayName = displayNameField.value,
                            email = emailField.value,
                            password = passwordField.value,
                            terms = terms,
                        ),
                    )
                }
            if (result != null) {
                root.removeAll()
                renderFriendRegistrationConfirmation(root)
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

/** Wie [buildRegistrationInput]: Name und E-Mail getrimmt, das Passwort nie. */
internal fun buildFriendRegistrationInput(
    displayName: String,
    email: String,
    password: String,
    terms: FriendTermsDto,
): FriendRegistrationInput =
    FriendRegistrationInput(
        displayName = displayName.trim(),
        email = email.trim(),
        password = password,
        termsVersion = terms.version,
        termsSha256 = terms.sha256,
    )

private fun renderFriendRegistrationConfirmation(root: SimplePanel) {
    // V1.4.7: root.removeAll() (caller) cleared the lockup added in
    // renderFriendRegistrationScreen too -- re-add it here, this is the SAME card, a follow-up
    // state, not a new screen (S13).
    root.brandLockup()
    root.pageHeader(tr("Freund-Konto angelegt"))
    root.p(
        tr(
            "Ihr Freund-Konto ist sofort nutzbar -- Sie können sich jetzt mit Ihrem gewählten Passwort " +
                "anmelden und an Videokonferenzen teilnehmen, zu denen Sie eingeladen wurden.",
        ),
    )
    root.link(tr("Zur Anmeldung"), url = "#${Routes.LOGIN}")
}
