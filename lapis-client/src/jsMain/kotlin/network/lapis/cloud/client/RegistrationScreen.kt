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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.MembershipAgreementDto
import network.lapis.cloud.shared.domain.RegionalChapterRefDto
import network.lapis.cloud.shared.domain.RegistrationInput
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.IRegistrationService
import network.lapis.cloud.shared.rpc.RegionalChapterRequiredException

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
        // Welle V1.9.14 -- loaded in parallel: neither depends on the other, and
        // `loadRegionalChapterOptionsOrEmpty` never shows a toast (see its own KDoc), so a failure
        // there never blocks the registration form itself.
        val (agreement, chapters) =
            coroutineScope {
                val agreementDeferred = async { guarded { rpcService<IRegistrationService>().getMembershipAgreement() } }
                val chaptersDeferred = async { loadRegionalChapterOptionsOrEmpty() }
                agreementDeferred.await() to chaptersDeferred.await()
            }
        loadingNotice.hide()
        if (agreement != null) renderRegistrationForm(root, agreement, chapters)
    }
}

private fun renderRegistrationForm(
    root: SimplePanel,
    agreement: MembershipAgreementDto,
    chapters: List<RegionalChapterRefDto> = emptyList(),
) {
    root.h2(gettext("Beitrittsvertrag (Version %1)", agreement.version)) { addCssClass("h5") }
    root.div {
        addCssClasses("border rounded p-2 mb-2")
        maxHeight = 240.px
        overflow = Overflow.AUTO
        content = sanitizeUntrustedI18nText(agreement.text)
    }

    val form = root.lapisForm()
    // Vier bis fünf Felder, alle bis auf den optionalen Landesverband Pflicht (Welle V1.9.14 macht
    // aus Fall (b) ggf. Fall (a) -- der Formular-Baustein entscheidet das automatisch in `buttons()`).
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
    // Welle V1.9.14 -- MUST be built before `form.buttons(...)` (plan §1 P9), only when chapters
    // exist at all. Optional -- the board can assign one later via `MemberAdministrationScreen.kt`.
    val chapterField =
        if (chapters.isNotEmpty()) {
            form.selectField(
                label = tr("Landesverband"),
                options = listOf("" to tr("— weiß ich noch nicht —")) + untrustedOptions(chapters.map { it.id to it.name }),
                hint = tr("Optional. Der Vorstand kann die Zuordnung später vornehmen."),
            )
        } else {
            null
        }

    val submitButton = Button(tr("Antrag einreichen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = submitButton)
    submitButton.onClick {
        form.submit(submitButton) {
            val chapterId = chapterField?.value
            // Welle V1.9.14 -- registerApplication runs in ITS OWN try, ahead of the `guarded {}`
            // fallback (plan §2.8): RegionalChapterRequiredException/a chapter-shaped
            // BadRequestException are shown AT THE FIELD; everything else (including a
            // non-chapter-shaped BadRequestException) delegates to `guarded {}`'s own handling via
            // `guarded { throw e }`, so the account-enumeration-hardening posture
            // (`IRegistrationService.registerApplication` KDoc) is preserved for every OTHER error.
            val result =
                try {
                    rpcService<IRegistrationService>().registerApplication(
                        buildRegistrationInput(
                            displayName = displayNameField.value,
                            email = emailField.value,
                            password = passwordField.value,
                            agreement = agreement,
                            regionalChapterId = chapterId,
                        ),
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: RegionalChapterRequiredException) {
                    if (chapterField != null) {
                        chapterField.showError(tr("Bitte wählen Sie einen Landesverband."))
                        null
                    } else {
                        guarded { throw e }
                    }
                } catch (e: BadRequestException) {
                    if (chapterField != null && !chapterId.isNullOrBlank()) {
                        chapterField.showError(tr("Dieser Landesverband ist nicht mehr verfügbar -- bitte Seite neu laden."))
                        null
                    } else {
                        guarded { throw e }
                    }
                } catch (e: Throwable) {
                    guarded { throw e }
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
    regionalChapterId: String? = null,
): RegistrationInput =
    RegistrationInput(
        displayName = displayName.trim(),
        email = email.trim(),
        password = password,
        agreementVersion = agreement.version,
        agreementSha256 = agreement.sha256,
        // Welle V1.9.14 -- blank/`null` alike become `null` (an empty select value "— weiß ich noch
        // nicht —" is `""`, never a real chapter id).
        regionalChapterId = regionalChapterId?.takeIf { it.isNotBlank() },
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
