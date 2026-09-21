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
            maxWidth = NARROW_FORM_MAX_WIDTH_PX.px
            width = 100.perc
            marginTop = 32.px
        }
    // V1.4.7 "Root-Verlinkung" -- Marken-Lockup über der Karte, siehe LoginScreen.kt für dasselbe
    // Muster. root.h1 bleibt unverändert der screenspezifische Titel.
    root.brandLockup()
    root.pageHeader(tr("Mitglied werden"))
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
    root.h2(gettext("Beitrittsvertrag (Version %1)", agreement.version)) { addCssClass("h5") }
    root.div {
        addCssClasses("border rounded p-2 mb-2")
        maxHeight = 240.px
        overflow = Overflow.AUTO
        content = agreement.text
    }

    val form = root.lapisForm()
    // Vier Felder, alle Pflicht: keine Sterne, Legende "Alle Felder sind Pflichtfelder." (Fall b).
    val displayNameField = form.textField(label = tr("Name"), required = true)
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
        label = tr("Ich habe den Beitrittsvertrag gelesen und akzeptiere ihn."),
        required = true,
        requiredMessage = gettext("Bitte bestätigen Sie, dass Sie den Beitrittsvertrag gelesen haben."),
    )

    val submitButton = Button(tr("Antrag einreichen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = submitButton)
    submitButton.onClick {
        form.submit(submitButton) {
            val result =
                guarded {
                    rpcService<IRegistrationService>().registerApplication(
                        buildRegistrationInput(
                            displayName = displayNameField.value,
                            email = emailField.value,
                            password = passwordField.value,
                            agreement = agreement,
                        ),
                    )
                }
            if (result != null) {
                root.removeAll()
                renderRegistrationPending(root)
            }
        }
    }

    root.div {
        marginTop = 8.px
        link(tr("Bereits Mitglied? Zur Anmeldung."), url = "#${Routes.LOGIN}")
    }
}

/**
 * Baut die Anfrage aus den ROHEN Feldwerten: Name und E-Mail werden getrimmt, das Passwort NIE -- ein Passwort darf führende
 * oder abschließende Leerzeichen tragen, und ein getrimmtes wäre ein anderes als das gewählte (der Nutzer könnte sich danach
 * nicht mehr anmelden). Eigene Funktion, damit ein Test das gebaute Objekt Feld für Feld prüfen kann (die zwei `String`-Felder
 * Name und E-Mail sind sonst vertauschbar, ohne dass der Compiler es merkt).
 */
internal fun buildRegistrationInput(
    displayName: String,
    email: String,
    password: String,
    agreement: MembershipAgreementDto,
): RegistrationInput =
    RegistrationInput(
        displayName = displayName.trim(),
        email = email.trim(),
        password = password,
        agreementVersion = agreement.version,
        agreementSha256 = agreement.sha256,
    )

private fun renderRegistrationPending(root: SimplePanel) {
    // V1.4.7: root.removeAll() (caller) cleared the lockup added in renderRegistrationScreen too --
    // re-add it here, this is the SAME card, a follow-up state, not a new screen (S13).
    root.brandLockup()
    root.pageHeader(tr("Antrag eingereicht"))
    root.p(
        tr(
            "Ihr Mitgliedschaftsantrag wurde eingereicht und wird vom Vorstand geprüft. " +
                "Sie sind noch nicht angemeldet -- nach der Freigabe können Sie sich mit Ihrem gewählten " +
                "Passwort anmelden.",
        ),
    )
    root.link(tr("Zur Anmeldung"), url = "#${Routes.LOGIN}")
}
