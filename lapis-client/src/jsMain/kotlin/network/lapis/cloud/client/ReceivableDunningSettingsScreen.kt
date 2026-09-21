package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import io.kvision.form.check.CheckBox
import io.kvision.form.check.checkBox
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.table.ResponsiveType
import io.kvision.table.Table
import io.kvision.table.TableType
import io.kvision.table.cell
import io.kvision.table.row
import io.kvision.table.table
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.ReceivableDunningLevelDto
import network.lapis.cloud.shared.domain.ReceivableDunningLevelInput
import network.lapis.cloud.shared.domain.ReceivableDunningSettingsDto
import network.lapis.cloud.shared.rpc.IReceivableDunningService

/**
 * Welle V1.4.21 -- `/receivable-dunning-settings`, ADMIN-only (`Routes.RECEIVABLE_DUNNING_SETTINGS`
 * KDoc): Schalter und Mahnstufen-CRUD des **Forderungs**-Mahnwesens (offene Debitor-Posten).
 * Strukturell an `DunningSettingsScreen.kt` (Beitrags-Mahnwesen) angelehnt, aber bewusst ohne
 * dessen Disclaimer-Flow -- `enableReceivableDunning()` nimmt kein Argument (siehe
 * `ReceivableDunningService` KDoc "drei Sicherungen", Stolperfalle S12). Ebenso eine eigene
 * Validierung ([validateReceivableDunningLevelInput]) statt der des Beitrags-Mahnwesens.
 *
 * Mahnstufen werden deaktiviert, nie gelöscht (es gibt keine Löschoperation); es gibt keine
 * Audit-Spur für die Konfiguration selbst (`ReceivableDunningLevelSnapshot` wird nirgends
 * geschrieben) -- beides steht als bekannte Lücke im CHANGELOG.
 */
fun renderReceivableDunningSettingsScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 700.px
            marginTop = 24.px
        }
    root.h1(tr("Forderungs-Mahnstufen"))
    root.p(
        tr(
            "Konfiguration des Mahnwesens für offene Forderungen (Debitoren) -- getrennt vom Mahnwesen für Mitgliedsbeiträge.",
        ),
    ) { addCssClasses("text-muted small") }

    renderReceivableStatusSection(root)
    renderReceivableLevelsSection(root)
}

// ================================================================================================
// Status + Aktivieren/Deaktivieren
// ================================================================================================

private fun renderReceivableStatusSection(root: SimplePanel) {
    root.h2(tr("Status")) { addCssClass("h5") }
    val settingsPanel = root.vPanel(spacing = 4)
    val actionsRow = root.hPanel(spacing = 8) { addCssClasses("mt-2") }
    val enableButton = actionsRow.button(tr("Forderungs-Mahnwesen aktivieren"), style = ButtonStyle.PRIMARY)
    val disableButton = actionsRow.button(tr("Forderungs-Mahnwesen deaktivieren"), style = ButtonStyle.OUTLINEDANGER)

    fun loadSettings() {
        settingsPanel.removeAll()
        settingsPanel.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val settings = guarded { rpcService<IReceivableDunningService>().getReceivableDunningSettings() }
            settingsPanel.removeAll()
            // S3: guarded() hat den Toast schon ausgelöst -- nur den Platzhalter entfernen.
            if (settings != null) renderReceivableSettingsSummary(settingsPanel, settings)
        }
    }

    enableButton.onClick {
        confirmDialog(
            title = tr("Forderungs-Mahnwesen aktivieren"),
            message =
                tr(
                    "Ab jetzt kann der Mahnlauf für überfällige Debitor-Posten Mahnhinweise ausstellen. Es wird kein " +
                        "PDF erzeugt und kein Brief versendet.",
                ),
            confirmLabel = tr("Aktivieren"),
        ) {
            runGuardedAction(enableButton) {
                val result = guarded { rpcService<IReceivableDunningService>().enableReceivableDunning() }
                if (result != null) {
                    notifySuccess(tr("Forderungs-Mahnwesen aktiviert."))
                    loadSettings()
                }
            }
        }
    }
    disableButton.onClick {
        confirmDialog(
            title = tr("Forderungs-Mahnwesen deaktivieren"),
            message =
                tr(
                    "Der automatische Mahnlauf stellt keine Mahnhinweise mehr aus. Bereits ausgestellte Mahnhinweise " +
                        "bleiben in der Historie erhalten.",
                ),
            confirmLabel = tr("Deaktivieren"),
        ) {
            runGuardedAction(disableButton) {
                val result = guarded { rpcService<IReceivableDunningService>().disableReceivableDunning() }
                if (result != null) {
                    notifySuccess(tr("Forderungs-Mahnwesen deaktiviert."))
                    loadSettings()
                }
            }
        }
    }
    loadSettings()
}

private fun renderReceivableSettingsSummary(
    panel: SimplePanel,
    settings: ReceivableDunningSettingsDto,
) {
    val statusRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    statusRow.div(tr("Status:")) { addCssClasses("text-muted small") }
    statusRow.statusBadge(
        if (settings.receivableDunningEnabled) tr("Aktiviert") else tr("Deaktiviert"),
        if (settings.receivableDunningEnabled) "success" else "secondary",
    )
    if (OpenItemAuthzUi.showNoActiveReceivableLevelWarning(settings)) {
        val band = panel.div { addCssClasses("alert alert-warning mt-2") }
        band.div(
            tr("Das Forderungs-Mahnwesen ist aktiviert, aber keine Mahnstufe ist aktiv -- es wird nichts gemahnt."),
        ) { addCssClass("fw-bold") }
    }
    if (OpenItemAuthzUi.showPollerDisabledWarning(settings)) {
        val band = panel.div { addCssClasses("alert alert-info mt-2") }
        band.div(
            tr(
                "Der automatische Mahnlauf ist auf dem Server nicht eingeschaltet (Umgebungsvariable " +
                    "LAPIS_RECEIVABLE_DUNNING_POLLER_ENABLED) -- Mahnhinweise lassen sich nur manuell ausstellen.",
            ),
        )
    }
    panel.div(gettext("Aktive Mahnstufen: %1", settings.activeLevelCount)) { addCssClasses("text-muted small") }
    panel.div(
        gettext("Automatischer Mahnlauf (Poller): %1", if (settings.pollerEnabled) gettext("aktiv") else gettext("inaktiv")),
    ) { addCssClasses("text-muted small") }
    panel.div(
        tr("Mahnhinweise erzeugen weder ein PDF noch einen Brief -- sie existieren nur in der Ansicht „Offene Posten“."),
    ) { addCssClasses("text-muted small") }
}

// ================================================================================================
// Mahnstufen
// ================================================================================================

private fun renderReceivableLevelsSection(root: SimplePanel) {
    root.h2(tr("Mahnstufen")) { addCssClass("h5") }
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val includeInactiveCheck = filterRow.checkBox(label = tr("Inaktive Stufen anzeigen"))
    val listPanel = root.vPanel(spacing = 6)

    // Generation counter: only the newest loadLevels() call may render. Without it, ticking and
    // un-ticking "Inaktive Stufen anzeigen" quickly lets the slower first response (with
    // includeInactive = true) overwrite the list after the second one.
    var levelsGeneration = 0

    fun loadLevels() {
        val generation = ++levelsGeneration
        val includeInactive = includeInactiveCheck.value
        listPanel.removeAll()
        listPanel.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val levels =
                guarded {
                    rpcService<IReceivableDunningService>().listReceivableDunningLevels(includeInactive = includeInactive)
                }
            if (generation != levelsGeneration) return@launch // a newer load has taken over
            listPanel.removeAll()
            if (levels == null) return@launch
            if (levels.isEmpty()) {
                listPanel.p(tr("Noch keine Mahnstufen konfiguriert."))
                return@launch
            }
            val table =
                listPanel.table(
                    headerNames = listOf(tr("Nr."), tr("Name"), tr("Wartefrist"), tr("Antwortfrist"), tr("Gebühr"), tr("Aktiv"), ""),
                    types = setOf(TableType.STRIPED, TableType.HOVER),
                    responsiveType = ResponsiveType.RESPONSIVE,
                )
            levels.sortedBy { it.levelNumber }.forEach { level -> renderReceivableLevelRow(table, level, ::loadLevels) }
        }
    }
    // KVision's `subscribe` fires once synchronously on registration with the current value (same
    // guard idiom as `MemberFamiliesScreen.kt`/`CostCentersScreen.kt`) -- skip that synthetic event,
    // the explicit `loadLevels()` below is the initial load.
    var isInitialIncludeInactiveEvent = true
    includeInactiveCheck.subscribe {
        if (isInitialIncludeInactiveEvent) {
            isInitialIncludeInactiveEvent = false
            return@subscribe
        }
        loadLevels()
    }
    loadLevels()

    root.h2(tr("Mahnstufe anlegen")) { addCssClass("h6") }
    renderReceivableLevelForm(root, existing = null, onSaved = ::loadLevels)
}

private fun renderReceivableLevelRow(
    table: Table,
    level: ReceivableDunningLevelDto,
    onChanged: () -> Unit,
) {
    table.row {
        cell(level.levelNumber.toString())
        cell(level.name)
        cell(gettext("%1 Tage", level.graceDays))
        cell(gettext("%1 Tage", level.responseDays))
        cell { level.feeAmount?.let { moneySpan(it) } ?: div("–") }
        val activeCell = cell()
        activeCell.activeStatusBadge(level.active)
        val actionsCell = cell()
        val actions = actionsCell.tableActionGroup()
        actions.tableActionButton("fas fa-pen", gettext("Bearbeiten")).onClick { openEditReceivableLevel(level, onChanged) }
        if (level.active) {
            val deactivate = actions.tableActionButton("fas fa-ban", gettext("Deaktivieren"), ButtonStyle.OUTLINEDANGER)
            deactivate.onClick {
                confirmDialog(
                    title = tr("Mahnstufe deaktivieren"),
                    message =
                        gettext(
                            "Mahnstufe \"%1 · %2\" wirklich deaktivieren? Bereits ausgestellte Mahnhinweise bleiben " +
                                "erhalten, die Stufe steht aber für künftige Eskalationen nicht mehr zur Verfügung. " +
                                "Stufen werden nie gelöscht.",
                            level.levelNumber,
                            level.name,
                        ),
                    confirmLabel = tr("Deaktivieren"),
                ) {
                    runGuardedAction(deactivate) {
                        val result = guarded { rpcService<IReceivableDunningService>().deactivateReceivableDunningLevel(level.id) }
                        if (result != null) {
                            notifySuccess(tr("Mahnstufe deaktiviert."))
                            onChanged()
                        }
                    }
                }
            }
        }
    }
}

/** Formular pre-filled im Modal (kein zweites, paralleles Bearbeiten-Formular) -- Muster `DunningSettingsScreen`. */
private fun openEditReceivableLevel(
    level: ReceivableDunningLevelDto,
    onChanged: () -> Unit,
) {
    val modal = Modal(caption = gettext("Mahnstufe \"%1\" bearbeiten", level.name))
    // Die Primäraktion steht in der Modal-Fußleiste (Abbrechen links, Speichern rechts, R27), nicht im Formularkörper.
    renderReceivableLevelForm(modal, existing = level, modal = modal) {
        modal.hide()
        onChanged()
    }
    modal.show()
}

internal fun renderReceivableLevelForm(
    root: SimplePanel,
    existing: ReceivableDunningLevelDto?,
    modal: Modal? = null,
    onSaved: () -> Unit,
) {
    // Formular-Grammatik (V1.4.28): Stufennummer, Name und beide Fristen Pflicht, die Gebühr optional => Fall (a).
    val form = root.lapisForm()
    val levelNumberField =
        form.textField(
            label = tr("Stufennummer"),
            value = existing?.levelNumber?.toString(),
            required = true,
            hint = gettext("Mindestens %1.", 1),
            rule = { receivableLevelNumberCheck(it) },
        )
    val nameField =
        form.textField(
            label = tr("Name"),
            value = existing?.name,
            required = true,
            hint = gettext("Höchstens %1 Zeichen.", MAX_RECEIVABLE_LEVEL_NAME_LENGTH),
            rule = { receivableNameCheck(it) },
        )
    val graceDaysField =
        form.textField(
            label = tr("Wartefrist in Tagen"),
            value = existing?.graceDays?.toString(),
            required = true,
            hint = gettext("Zulässig: %1 bis %2.", MIN_RECEIVABLE_DUNNING_DAYS, MAX_RECEIVABLE_DUNNING_DAYS),
            rule = { FormRules.intInRange(value = it, min = MIN_RECEIVABLE_DUNNING_DAYS, max = MAX_RECEIVABLE_DUNNING_DAYS) },
        )
    val responseDaysField =
        form.textField(
            label = tr("Antwortfrist in Tagen"),
            value = existing?.responseDays?.toString(),
            required = true,
            hint = gettext("Zulässig: %1 bis %2.", MIN_RECEIVABLE_DUNNING_DAYS, MAX_RECEIVABLE_DUNNING_DAYS),
            rule = { FormRules.intInRange(value = it, min = MIN_RECEIVABLE_DUNNING_DAYS, max = MAX_RECEIVABLE_DUNNING_DAYS) },
        )
    val feeField =
        form.textField(
            label = tr("Gebühr in EUR"),
            value = existing?.feeAmount?.toString(),
            hint = gettext("Höchstens %1.", feeBound(MAX_RECEIVABLE_FEE_AMOUNT)),
            rule = { receivableFeeCheck(it) },
        )
    // Nur beim Bearbeiten: Reaktivierung einer deaktivierten Stufe (eine neu angelegte Stufe ist immer aktiv).
    val activeField = if (existing != null) form.checkField(value = existing.active, label = tr("Aktiv")) else null
    val submitButton = Button(if (existing == null) tr("Mahnstufe anlegen") else tr("Speichern"), style = ButtonStyle.PRIMARY)
    if (modal != null) {
        // Im Modal steht die Knopfzeile in der Fußleiste: Abbrechen links, bestätigende Aktion rechts (R27).
        form.finish()
        modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
        modal.addButton(submitButton)
    } else {
        form.buttons(primary = submitButton)
    }

    // N2: derselbe Betrags-Parser wie im Offene-Posten-Formular. Der frühere `replace(',', '.').toDoubleOrNull()` +
    // `roundToTwoDecimalPlaces` akzeptierte "1e2" und rundete "12,999" STILL auf 13,00 -- eine Gebühr, die der Nutzer so nie
    // eingegeben hat. `AmountInput.Empty` = keine Gebühr (das Feld ist optional).
    // Derselbe Parser wie die Feldregel [receivableFeeCheck], also auch mit `0` als gültiger Gebühr (der Server erlaubt 0,00 bis
    // 25,00; ein bloßes [parseAmountInput] lehnte 0 ab und machte eine Stufe mit Gebühr 0 unbearbeitbar).
    fun parsedFee(): Decimal? = (parseDunningFeeInput(feeField.value) as? AmountInput.Valid)?.value

    // Keine Querregel: jede Grenze von `validateReceivableDunningLevelInput` steht schon als Feldregel an ihrem Feld (die Zusatzprüfung
    // dort war nie erreichbar, sobald alle Felder für sich gültig sind).

    submitButton.onClick {
        form.submit(submitButton) {
            val result =
                guarded {
                    // Der Bau des Objekts steht IM Guard: ein Fehler dabei wird gemeldet statt in den AppScope zu entweichen.
                    val input =
                        buildReceivableDunningLevelInput(
                            levelNumber = levelNumberField.value,
                            name = nameField.value,
                            graceDays = graceDaysField.value,
                            responseDays = responseDaysField.value,
                            fee = parsedFee(),
                            active = (activeField?.control as CheckBox?)?.value ?: true,
                        )
                    val service = rpcService<IReceivableDunningService>()
                    if (existing == null) {
                        service.createReceivableDunningLevel(input)
                    } else {
                        service.updateReceivableDunningLevel(existing.id, input)
                    }
                }
            if (result != null) {
                notifySuccess(if (existing == null) tr("Mahnstufe angelegt.") else tr("Mahnstufe gespeichert."))
                if (existing == null) {
                    levelNumberField.reset()
                    nameField.reset()
                    graceDaysField.reset()
                    responseDaysField.reset()
                    feeField.reset()
                }
                onSaved()
            }
        }
    }
}

/** Wie [buildDunningLevelInput], für die Forderungs-Mahnstufe (der Name wird hier nicht gekürzt, sondern ab 100 Zeichen abgelehnt). */
internal fun buildReceivableDunningLevelInput(
    levelNumber: String,
    name: String,
    graceDays: String,
    responseDays: String,
    fee: Decimal?,
    active: Boolean,
): ReceivableDunningLevelInput =
    ReceivableDunningLevelInput(
        levelNumber = levelNumber.trim().toInt(),
        name = name.trim(),
        graceDays = graceDays.trim().toInt(),
        responseDays = responseDays.trim().toInt(),
        feeAmount = fee,
        active = active,
    )

private fun receivableLevelNumberCheck(text: String): FieldCheck {
    val number = text.trim().toIntOrNull()
    return if (number == null || number < 1) {
        FieldCheck.Invalid(gettext("Die Stufennummer muss mindestens 1 sein."))
    } else {
        FieldCheck.Ok
    }
}

private fun receivableNameCheck(text: String): FieldCheck =
    if (text.trim().length > MAX_RECEIVABLE_LEVEL_NAME_LENGTH) {
        FieldCheck.Invalid(gettext("Der Name muss zwischen 1 und %1 Zeichen lang sein.", MAX_RECEIVABLE_LEVEL_NAME_LENGTH))
    } else {
        FieldCheck.Ok
    }

/**
 * Feldregel der Gebühr: derselbe Betrags-Parser wie im Offene-Posten-Formular, aber mit `0` als gültiger Gebühr (der Server
 * erlaubt 0,00 bis 25,00, `ReceivableDunningService.validateLevelInput`) und der eigenen Obergrenze in der Meldung.
 */
internal fun receivableFeeCheck(text: String): FieldCheck = FormRules.optionalFee(text, MAX_RECEIVABLE_FEE_AMOUNT)
