package network.lapis.cloud.client

import io.kvision.html.Autocomplete
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
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.FriendRegistrationInput
import network.lapis.cloud.shared.domain.FriendTermsDto
import network.lapis.cloud.shared.rpc.AgreementTextOutdatedException
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
            maxWidth = WIDE_FORM_MAX_WIDTH_PX.px
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
        val terms = loadFriendTerms()
        loadingNotice.hide()
        if (terms != null) renderFriendRegistrationForm(root, terms)
    }
}

/** The ONE read of the current terms -- the first load and the reload after "the terms changed" both use it. `null` = failed (the person was told). */
private suspend fun loadFriendTerms(): FriendTermsDto? = guarded { rpcService<IRegistrationService>().getFriendTerms() }

private fun renderFriendRegistrationForm(
    root: SimplePanel,
    terms: FriendTermsDto,
) {
    // V1.9.134: the reading frame is replaceable in place -- after "the text changed" the form keeps what was typed.
    var currentTerms = terms
    val form = root.lapisForm()
    // Vier Felder, alle Pflicht: keine Sterne, Legende "Alle Felder sind Pflichtfelder." (Fall b).
    // V1.9.134: zwei Spalten ab 768 px, ein Feld je Zelle; lesen, zustimmen, absenden.
    val grid = form.panel.registrationFieldGrid()
    val displayNameField = form.textField(label = tr("Anzeigename (nicht überprüft)"), required = true, host = grid.gridCell())
    val emailField =
        form.textField(
            label = tr("E-Mail"),
            type = InputType.EMAIL,
            required = true,
            autocomplete = Autocomplete.USERNAME,
            rule = FormRules::email,
            host = grid.gridCell(),
        )
    // Die Passwortregel steht als Hinweis UNTER dem Feld (sichtbar, bevor getippt wird), nicht im Label.
    val passwordField =
        form.passwordField(
            label = tr("Passwort"),
            required = true,
            autocomplete = Autocomplete.NEW_PASSWORD,
            reveal = true,
            hint = gettext("Mindestens %1 Zeichen.", Validation.PASSWORD_MIN_LENGTH),
            rule = { FormRules.newPassword(value = it, email = emailField.value.trim()) },
            host = grid.gridCell(),
        )
    val confirmPasswordField =
        form.passwordField(
            label = tr("Passwort bestätigen"),
            required = true,
            autocomplete = Autocomplete.NEW_PASSWORD,
            revealedBy = passwordField,
            host = grid.gridCell(),
        )
    form.crossFieldRule(field = confirmPasswordField) {
        FormRules.passwordsMatch(password = passwordField.value, confirmation = confirmPasswordField.value)
    }
    val termsSection =
        AgreementTextSection(parent = form.panel, heading = { version ->
            gettext("Nutzungsbedingungen für Freund-Konten (Version %1)", version)
        })
    termsSection.show(version = terms.version, text = terms.text, format = terms.format, renderedHtml = terms.renderedHtml)
    // Die Zustimmung ist ein Feld der Grammatik (V1.4.29, `checkField`): Pflicht heißt "angekreuzt", der Fehler steht AM Feld
    // (nicht mehr in der Sammelfläche), `aria-required` setzt der Baustein.
    val consentField =
        form.checkField(
            label = tr("Ich habe die Nutzungsbedingungen für Freund-Konten gelesen und akzeptiere sie."),
            required = true,
            requiredMessage = gettext("Bitte bestätigen Sie, dass Sie die Nutzungsbedingungen gelesen haben."),
        )

    val submitButton = newActionButton(ActionIcon.ADD, tr("Freund-Konto anlegen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = submitButton, enterSubmits = true)
    submitButton.onClick {
        form.submit(submitButton) {
            val result =
                try {
                    rpcService<IRegistrationService>().registerFriend(
                        buildFriendRegistrationInput(
                            displayName = displayNameField.value,
                            email = emailField.value,
                            password = passwordField.value,
                            terms = currentTerms,
                        ),
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: AgreementTextOutdatedException) {
                    // V1.9.134 -- the terms were replaced while this form was open: a fixed toast (Kilua carries no message), the new text
                    // is loaded into the frame, the consent is withdrawn and everything typed stays.
                    notifyError(tr("Die Nutzungsbedingungen wurden aktualisiert. Bitte lesen Sie sie erneut."))
                    loadFriendTerms()?.let { fresh ->
                        currentTerms = fresh
                        termsSection.show(
                            version = fresh.version,
                            text = fresh.text,
                            format = fresh.format,
                            renderedHtml = fresh.renderedHtml,
                        )
                        consentField.reset()
                    }
                    null
                } catch (e: Throwable) {
                    guarded { throw e }
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

internal const val FRIEND_CONFIRMATION_NEW_CLASS = "lapis-friend-confirmation-new"
internal const val FRIEND_CONFIRMATION_KNOWN_CLASS = "lapis-friend-confirmation-known"

internal fun renderFriendRegistrationConfirmation(root: SimplePanel) {
    // V1.4.7: root.removeAll() (caller) cleared the lockup added in
    // renderFriendRegistrationScreen too -- re-add it here, this is the SAME card, a follow-up
    // state, not a new screen (S13).
    root.brandLockup()
    // Same text for a new and for a known address (account-enumeration hardening, see the class KDoc): the header does not claim that an account
    // was created, the first paragraph is conditional, the second tells a person who had registered before what to do instead of leaving them
    // waiting. The server answer and the mail behaviour are unchanged.
    root.pageHeader(tr("Registrierung abgeschickt"))
    root
        .p(
            tr(
                "Ist diese Adresse neu, ist Ihr Freund-Konto sofort nutzbar -- Sie können sich jetzt mit Ihrem gewählten Passwort " +
                    "anmelden und an Videokonferenzen teilnehmen, zu denen Sie eingeladen wurden.",
            ),
        ).addCssClass(FRIEND_CONFIRMATION_NEW_CLASS)
    root
        .p(
            tr(
                "Hatten Sie sich mit dieser Adresse schon einmal registriert, wurde kein neues Konto angelegt und Ihr eben gewähltes " +
                    "Passwort nicht übernommen. Melden Sie sich dann mit Ihrem bisherigen Passwort an oder nutzen Sie auf der " +
                    "Anmeldeseite „Passwort vergessen?“.",
            ),
        ).addCssClass(FRIEND_CONFIRMATION_KNOWN_CLASS)
    root.link(tr("Zur Anmeldung"), url = "#${Routes.LOGIN}")
}
