package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto
import network.lapis.cloud.shared.rpc.IAccountingService
import network.lapis.cloud.shared.rpc.ITravelExpenseService

/**
 * Welle V1.9.122 -- the travel expense settings page, `Routes.TRAVEL_EXPENSE_SETTINGS`. The settings form is **ADMIN only** (the same gate as the writer
 * `ITravelExpenseService.updateTravelExpenseSettings`). It holds everything the example form leaves to the instance: the three day-allowance
 * tiers (hours and amounts), the meal deduction percentages, the mileage and passenger rates, the contact person members see on the travel
 * expense screen, and the two optional account mappings the booking needs for an advance and for a donation waiver.
 *
 * The defaults are the **example template** (based on a 2024 travel-expense form) -- provisional, NOT the statutory 14/28 EUR; the
 * page says so in a fixed notice. "Beispielwerte der Vorlage übernehmen" only FILLS the form, nothing is saved until the administration
 * presses "Speichern". Every field rule mirrors a server range; the server validates again before the database.
 */
fun renderTravelExpenseSettingsScreen(container: SimplePanel) {
    val isAdmin = AppState.hasRole(AccountRole.ADMIN)
    // V1.9.199: a mixed page -- the settings form keeps the form measure (formSection), the documents table below it uses the full width.
    val root =
        container.pageShell(PageWidth.WIDE, spacing = 14)
    root.pageHeader(tr("Reisekosten-Einstellungen"), intro = PageIntros.forRoute(Routes.TRAVEL_EXPENSE_SETTINGS))
    // Order on the page: the form (ADMIN) first, the documents below it, outside the form.
    val formRoot = if (isAdmin) root.formSection() else null
    if (!isAdmin) {
        root.p(tr("Die Erstattungssätze und den Ansprechpartner pflegt die Administration.")) { addCssClasses("text-muted small") }
    }
    val docsHost = root.div()

    fun renderDocuments(rates: TravelExpenseRatesDto) {
        docsHost.removeAll()
        renderTravelExpenseDocumentsAdmin(docsHost, rates) {
            // Documents act at once: only this section is reloaded, never the settings form (unsaved edits above stay).
            AppScope.launch {
                val fresh = guarded { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
                if (fresh ==
                    null
                ) {
                    docsHost.dataErrorState(onRetry = { reloadDocuments(docsHost, ::renderDocuments) })
                } else {
                    renderDocuments(fresh)
                }
            }
        }
    }
    if (formRoot == null) {
        // BOARD: only the documents. Rates and contact person stay with the administration.
        reloadDocuments(docsHost, ::renderDocuments)
        return
    }
    formRoot.div(
        tr(
            "Die Beispielwerte stammen aus einer Vorlage (Reisekostenabrechnung 2024) und sind vorläufig. " +
                "Sie entsprechen nicht den gesetzlichen Pauschalen (14 € / 28 €). Bitte prüfen Sie sie, bevor Mitglieder abrechnen.",
        ),
        className = "alert alert-warning",
    )
    val body = formRoot.vPanel(spacing = 8)

    fun load() {
        body.removeAll()
        body.p(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val rates = guarded { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
            val accounts = guarded { rpcService<IAccountingService>().listLedgerAccounts(activeOnly = true) }
            body.removeAll()
            if (rates == null || accounts == null) {
                // R34: a failed load is an error state with a retry, never an empty or half-filled form.
                body.dataErrorState(onRetry = { load() })
                return@launch
            }
            renderDocuments(rates)
            val accountOptions =
                listOf("" to tr("(nicht konfiguriert)")) + untrustedOptions(accounts.map { it.id to "${it.accountNumber} · ${it.name}" })
            val current = travelExpenseSettingsFormValues(rates)

            val templateButton = body.button(tr("Beispielwerte der Vorlage übernehmen"), style = ButtonStyle.OUTLINESECONDARY)
            val form = body.lapisForm()

            form.panel.h2(tr("Tagegeld nach Abwesenheit")) { addCssClass("h6") }
            val tier1Hours =
                form.textField(
                    label = tr("Stufe 1: ab (Stunden)"),
                    value = current.tier1Hours,
                    required = true,
                    rule = hoursRule(),
                )
            val tier1Eur =
                form.textField(
                    label = tr("Stufe 1: Betrag (EUR)"),
                    value = current.tier1Eur,
                    required = true,
                    rule = centsRule(),
                )
            val tier2Hours =
                form.textField(
                    label = tr("Stufe 2: ab (Stunden)"),
                    value = current.tier2Hours,
                    required = true,
                    rule = hoursRule(),
                )
            val tier2Eur =
                form.textField(
                    label = tr("Stufe 2: Betrag (EUR)"),
                    value = current.tier2Eur,
                    required = true,
                    rule = centsRule(),
                )
            val tier3Hours =
                form.textField(
                    label = tr("Stufe 3: ab (Stunden)"),
                    value = current.tier3Hours,
                    required = true,
                    rule = hoursRule(),
                )
            val tier3Eur =
                form.textField(
                    label = tr("Stufe 3: Betrag (EUR)"),
                    value = current.tier3Eur,
                    required = true,
                    rule = centsRule(),
                )
            form.crossFieldRule(field = tier3Hours) {
                val hours = listOf(tier1Hours, tier2Hours, tier3Hours).map { it.value.trim().toIntOrNull() }
                if (hours.any { it == null }) {
                    FieldCheck.Ok
                } else if (hours[0]!! >= hours[1]!! || hours[1]!! >= hours[2]!!) {
                    FieldCheck.Invalid(gettext("Die Stunden müssen von Stufe zu Stufe größer werden."))
                } else {
                    FieldCheck.Ok
                }
            }
            form.crossFieldRule(field = tier3Eur) {
                val cents = listOf(tier1Eur, tier2Eur, tier3Eur).map { travelExpenseParseCents(it.value) }
                if (cents.any { it == null }) {
                    FieldCheck.Ok
                } else if (cents[0]!! > cents[1]!! || cents[1]!! > cents[2]!!) {
                    FieldCheck.Invalid(gettext("Die Beträge dürfen von Stufe zu Stufe nicht sinken."))
                } else {
                    FieldCheck.Ok
                }
            }

            form.panel.h2(tr("Abzug für gestellte Mahlzeiten")) { addCssClass("h6") }
            val breakfast =
                form.textField(
                    label = tr("Frühstück (% von Stufe 3)"),
                    value = current.breakfastPercent,
                    required = true,
                    rule = { FormRules.intInRange(it, 0, 100) },
                )
            val lunch =
                form.textField(
                    label = tr("Mittagessen (% von Stufe 3)"),
                    value = current.lunchPercent,
                    required = true,
                    rule = { FormRules.intInRange(it, 0, 100) },
                )
            val dinner =
                form.textField(
                    label = tr("Abendessen (% von Stufe 3)"),
                    value = current.dinnerPercent,
                    required = true,
                    rule = { FormRules.intInRange(it, 0, 100) },
                )

            form.panel.h2(tr("Fahrtkosten")) { addCssClass("h6") }
            val mileage =
                form.textField(
                    label = tr("Kilometersatz (EUR/km)"),
                    value = current.mileageEur,
                    hint = tr("Beispiel: 0,30. Leer lassen, wenn keine Kilometer abgerechnet werden. Höchstens 1,00."),
                    rule = { travelExpenseMileageRateCheck(it) },
                )
            val passenger =
                form.textField(
                    label = tr("Zuschlag je Mitfahrer (EUR/km)"),
                    value = current.passengerEur,
                    required = true,
                    hint = tr("Beispiel: 0,02. Höchstens 0,20."),
                    rule = { travelExpensePassengerRateCheck(it) },
                )
            val perDiemLegacy =
                form.textField(
                    label = tr("Tagespauschale für Altanträge (EUR)"),
                    value = current.perDiemLegacyEur,
                    hint = tr("Nur für Anträge, die vor der Umstellung angelegt wurden. Leer lassen, wenn nicht benötigt."),
                    rule = { travelExpenseRateFieldCheck(it, gettext("Bitte eine gültige Tagespauschale angeben (z. B. 14.00).")) },
                )

            form.panel.h2(tr("Ansprechpartner für Reisekosten")) { addCssClass("h6") }
            form.panel.p(tr("Diese Angaben sehen alle Mitglieder auf der Seite Reisekosten.")) { addCssClasses("text-muted small") }
            val contactName =
                form.textField(
                    label = tr("Name"),
                    value = current.contactName,
                    rule = { FormRules.maxLength(it, TravelExpenseAmountRules.MAX_CONTACT_NAME_LENGTH) },
                )
            val contactEmail =
                form.textField(
                    label = tr("E-Mail"),
                    value = current.contactEmail,
                    rule = { if (it.isBlank()) FieldCheck.Ok else FormRules.email(it) },
                )
            val contactPhone =
                form.textField(
                    label = tr("Telefon"),
                    value = current.contactPhone,
                    hint = tr("Nur Ziffern, Leerzeichen und + ( ) / -."),
                    rule = { travelExpensePhoneCheck(it) },
                )
            val contactHint = form.travelContactHintEditor(current = current.contactHint, format = current.contactHintFormat)

            form.panel.h2(tr("Buchung")) { addCssClass("h6") }
            val advanceAccount =
                form.selectField(
                    label = tr("Konto für bereits gezahlte Vorschüsse"),
                    options = accountOptions,
                    value = current.advanceAccountId,
                    hint = tr("Nötig, sobald ein Antrag einen Vorschuss enthält."),
                )
            val waiverAccount =
                form.selectField(
                    label = tr("Ertragskonto für Aufwandsspenden"),
                    options = accountOptions,
                    value = current.donationWaiverAccountId,
                    hint = tr("Nötig, sobald ein Mitglied auf die Erstattung verzichtet."),
                )

            val allFields =
                listOf(
                    tier1Hours,
                    tier1Eur,
                    tier2Hours,
                    tier2Eur,
                    tier3Hours,
                    tier3Eur,
                    breakfast,
                    lunch,
                    dinner,
                    mileage,
                    passenger,
                    perDiemLegacy,
                )
            templateButton.onClick {
                val template = travelExpensePdvTemplateValues(current.copy())
                tier1Hours.setValue(template.tier1Hours)
                tier2Hours.setValue(template.tier2Hours)
                tier3Hours.setValue(template.tier3Hours)
                tier1Eur.setValue(template.tier1Eur)
                tier2Eur.setValue(template.tier2Eur)
                tier3Eur.setValue(template.tier3Eur)
                breakfast.setValue(template.breakfastPercent)
                lunch.setValue(template.lunchPercent)
                dinner.setValue(template.dinnerPercent)
                passenger.setValue(template.passengerEur)
                allFields.forEach { it.validate(force = false) }
                notifySuccess(tr("Standardwerte eingetragen. Zum Übernehmen bitte speichern."))
            }

            val saveButton = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
            form.buttons(primary = saveButton)
            saveButton.onClick {
                form.submit(saveButton) {
                    val input =
                        travelExpenseSettingsInput(
                            TravelExpenseSettingsFormValues(
                                tier1Hours = tier1Hours.value,
                                tier2Hours = tier2Hours.value,
                                tier3Hours = tier3Hours.value,
                                tier1Eur = tier1Eur.value,
                                tier2Eur = tier2Eur.value,
                                tier3Eur = tier3Eur.value,
                                breakfastPercent = breakfast.value,
                                lunchPercent = lunch.value,
                                dinnerPercent = dinner.value,
                                mileageEur = mileage.value,
                                passengerEur = passenger.value,
                                perDiemLegacyEur = perDiemLegacy.value,
                                contactName = contactName.value,
                                contactEmail = contactEmail.value,
                                contactPhone = contactPhone.value,
                                contactHint = contactHint.field.value,
                                contactHintFormat = contactHint.formatToSend(),
                                advanceAccountId = advanceAccount.value,
                                donationWaiverAccountId = waiverAccount.value,
                            ),
                        )
                    if (input == null) {
                        form.showFormError(tr("Bitte prüfen Sie die Eingaben."))
                        return@submit
                    }
                    val result = guarded { rpcService<ITravelExpenseService>().updateTravelExpenseSettings(input) }
                    if (result != null) {
                        notifySuccess(tr("Einstellungen gespeichert."))
                        load()
                    }
                }
            }
        }
    }
    load()
}

private fun hoursRule(): (String) -> FieldCheck = { FormRules.intInRange(it, 1, 24) }

private fun centsRule(): (String) -> FieldCheck =
    { value ->
        val cents = travelExpenseParseCents(value)
        when {
            cents == null -> FieldCheck.Invalid(gettext("Bitte einen Betrag wie 1234,56 eingeben (ohne Tausendertrennzeichen)."))
            cents > TRAVEL_EXPENSE_MAX_TIER_CENTS ->
                FieldCheck.Invalid(
                    gettext("Der Betrag ist zu groß (höchstens %1).", formatMoney(centsToDecimal(TRAVEL_EXPENSE_MAX_TIER_CENTS))),
                )
            else -> FieldCheck.Ok
        }
    }

/** The server's upper bound for one tier amount (`chk_os_travel_tier_cents`: 100.00 EUR). */
private const val TRAVEL_EXPENSE_MAX_TIER_CENTS = 10_000L

/** Mileage rate: blank = not configured; otherwise whole cents between 0.01 and 1.00 EUR per km. */
internal fun travelExpenseMileageRateCheck(value: String): FieldCheck {
    if (value.isBlank()) return FieldCheck.Ok
    val cents = travelExpenseParseCents(value)
    return when {
        cents == null -> FieldCheck.Invalid(gettext("Bitte einen Betrag wie 1234,56 eingeben (ohne Tausendertrennzeichen)."))
        cents < 1 -> FieldCheck.Invalid(gettext("Der Betrag muss größer als 0 sein."))
        cents > 100 -> FieldCheck.Invalid(gettext("Der Betrag ist zu groß (höchstens %1).", formatMoney(centsToDecimal(100))))
        else -> FieldCheck.Ok
    }
}

/** Passenger surcharge per km: whole cents between 0.00 and 0.20 EUR. */
internal fun travelExpensePassengerRateCheck(value: String): FieldCheck {
    val cents = travelExpenseParseCents(value)
    return when {
        cents == null -> FieldCheck.Invalid(gettext("Bitte einen Betrag wie 1234,56 eingeben (ohne Tausendertrennzeichen)."))
        cents > 20 -> FieldCheck.Invalid(gettext("Der Betrag ist zu groß (höchstens %1).", formatMoney(centsToDecimal(20))))
        else -> FieldCheck.Ok
    }
}

private val PHONE_SHAPE = Regex("^[+0-9 ()/\\-]{3,40}$")

/** Phone: blank is fine; otherwise digits, blanks and `+ ( ) / -` only (mirrors the server). */
internal fun travelExpensePhoneCheck(value: String): FieldCheck =
    if (value.isBlank() || PHONE_SHAPE.matches(value.trim())) {
        FieldCheck.Ok
    } else {
        FieldCheck.Invalid(gettext("Bitte nur Ziffern, Leerzeichen und + ( ) / - verwenden."))
    }

/** Loads the rates and hands them to [render]; a failed load is an error state with a retry (R34). */
private fun reloadDocuments(
    host: io.kvision.core.Container,
    render: (TravelExpenseRatesDto) -> Unit,
) {
    AppScope.launch {
        val rates = guarded { rpcService<ITravelExpenseService>().getTravelExpenseRates() }
        if (rates == null) host.dataErrorState(onRetry = { reloadDocuments(host, render) }) else render(rates)
    }
}
