package network.lapis.cloud.client

import io.kvision.html.Autocomplete
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
import network.lapis.cloud.shared.rpc.AgreementTextOutdatedException
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
            maxWidth = WIDE_FORM_MAX_WIDTH_PX.px
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
                val agreementDeferred = async { loadMembershipAgreement() }
                val chaptersDeferred = async { loadRegionalChapterOptionsOrEmpty() }
                agreementDeferred.await() to chaptersDeferred.await()
            }
        loadingNotice.hide()
        if (agreement != null) renderRegistrationForm(root, agreement, chapters)
    }
}

/** The ONE read of the current text -- the first load and the reload after "the text changed" both use it. `null` = failed (the person was told). */
private suspend fun loadMembershipAgreement(): MembershipAgreementDto? =
    guarded {
        rpcService<IRegistrationService>().getMembershipAgreement()
    }

private fun renderRegistrationForm(
    root: SimplePanel,
    agreement: MembershipAgreementDto,
    chapters: List<RegionalChapterRefDto> = emptyList(),
) {
    // V1.9.134: the reading frame is replaceable in place -- after "the text changed" the form keeps what was typed.
    var currentAgreement = agreement
    val form = root.lapisForm()
    // Vier bis fünf Felder, alle bis auf den optionalen Landesverband Pflicht (Welle V1.9.14 macht
    // aus Fall (b) ggf. Fall (a) -- der Formular-Baustein entscheidet das automatisch in `buttons()`).
    // V1.9.134: zwei Spalten ab 768 px, ein Feld je Zelle (DOM-Reihenfolge = Zeilenreihenfolge); lesen, zustimmen, absenden.
    val grid = form.panel.registrationFieldGrid()
    val displayNameField = form.textField(label = tr("Name"), required = true, host = grid.gridCell())
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
    // Welle V1.9.14 -- MUST be built before `form.buttons(...)` (plan §1 P9), only when chapters
    // exist at all. Optional -- the board can assign one later via `MemberAdministrationScreen.kt`.
    // V1.9.134: über die volle Breite, weil es kein zweites Feld als Partner gibt.
    val chapterField =
        if (chapters.isNotEmpty()) {
            form.selectField(
                label = tr("Landesverband"),
                options = listOf("" to tr("— weiß ich noch nicht —")) + untrustedOptions(chapters.map { it.id to it.name }),
                hint = tr("Optional. Der Vorstand kann die Zuordnung später vornehmen."),
                host = grid.gridCell(full = true),
            )
        } else {
            null
        }

    // V1.9.141 -- optional personal details: country of residence and nationality. NO preselection (the pinned country only orders
    // the list). Built before `form.buttons(...)` and before the agreement, like the chapter field. Nothing here is required.
    form.panel.h2(tr("Angaben zur Person (freiwillig)")) { addCssClass("h6") }
    form.panel.p(tr("Diese Angaben sind freiwillig. Sie können sie später jederzeit in Ihrem Profil ergänzen oder ändern.")) {
        addCssClasses("text-muted small")
    }
    val personGrid = form.panel.registrationFieldGrid()
    val countryField = personGrid.gridCell().countrySelect(value = null, label = tr("Land des Wohnsitzes"))
    val nationalityField = personGrid.gridCell().nationalityMultiSelect(label = tr("Staatsangehörigkeit"), initial = emptyList())
    val countryError =
        form.panel.div {
            addCssClasses("text-danger small")
            setAttribute("role", "alert")
            visible = false
        }

    val agreementSection =
        AgreementTextSection(parent = form.panel, heading = { version -> gettext("Beitrittsvertrag (Version %1)", version) })
    agreementSection.show(
        version = agreement.version,
        text = agreement.text,
        format = agreement.format,
        renderedHtml = agreement.renderedHtml,
    )
    // Die Zustimmung ist ein Feld der Grammatik (V1.4.29, `checkField`): Pflicht heißt "angekreuzt", der Fehler steht AM Feld
    // (nicht mehr in der Sammelfläche), `aria-required` setzt der Baustein.
    val consentField =
        form.checkField(
            label = tr("Ich habe den Beitrittsvertrag gelesen und akzeptiere ihn."),
            required = true,
            requiredMessage = gettext("Bitte bestätigen Sie, dass Sie den Beitrittsvertrag gelesen haben."),
        )

    val submitButton = newActionButton(ActionIcon.SEND, tr("Antrag einreichen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = submitButton, enterSubmits = true)
    submitButton.onClick {
        form.submit(submitButton) {
            val chapterId = chapterField?.value
            val countryCode = countryField.value?.takeIf { it.isNotBlank() }
            val nationalityCodes = nationalityField.codes
            countryError.visible = false
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
                            agreement = currentAgreement,
                            regionalChapterId = chapterId,
                            countryCode = countryCode,
                            nationalityCodes = nationalityCodes,
                        ),
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: AgreementTextOutdatedException) {
                    // V1.9.134 -- the text was replaced while this form was open: a fixed toast (Kilua carries no message), the new text
                    // is loaded into the frame, the consent is withdrawn and everything typed stays.
                    notifyError(tr("Der Vertrag wurde aktualisiert. Bitte lesen Sie ihn erneut."))
                    loadMembershipAgreement()?.let { fresh ->
                        currentAgreement = fresh
                        agreementSection.show(
                            version = fresh.version,
                            text = fresh.text,
                            format = fresh.format,
                            renderedHtml = fresh.renderedHtml,
                        )
                        consentField.reset()
                    }
                    null
                } catch (e: RegionalChapterRequiredException) {
                    if (chapterField != null) {
                        chapterField.showError(tr("Bitte wählen Sie einen Landesverband."))
                        null
                    } else {
                        guarded { throw e }
                    }
                } catch (e: BadRequestException) {
                    // Kilua carries no message, so the rejected field cannot be told apart: show the chapter hint when a chapter was
                    // chosen AND the country hint when a country/nationality was given (the list only offers valid values, so this
                    // only happens with a stale client).
                    val chapterChosen = chapterField != null && !chapterId.isNullOrBlank()
                    val countryGiven = countryCode != null || nationalityCodes.isNotEmpty()
                    if (chapterChosen) {
                        chapterField?.showError(tr("Dieser Landesverband ist nicht mehr verfügbar -- bitte Seite neu laden."))
                    }
                    if (countryGiven) {
                        countryError.content = gettext("Bitte prüfen Sie die Angaben zu Land und Staatsangehörigkeit.")
                        countryError.visible = true
                    }
                    if (chapterChosen || countryGiven) {
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
    countryCode: String? = null,
    nationalityCodes: List<String> = emptyList(),
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
        // V1.9.141 -- optional; a blank select value is "not given", never a code.
        countryCode = countryCode?.takeIf { it.isNotBlank() },
        nationalityCodes = nationalityCodes,
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
