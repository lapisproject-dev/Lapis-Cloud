package network.lapis.cloud.client

import dev.kilua.rpc.types.Decimal
import dev.kilua.rpc.types.toDouble
import io.kvision.core.Overflow
import io.kvision.form.check.CheckBox
import io.kvision.form.check.checkBox
import io.kvision.form.text.Text
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.table.Table
import io.kvision.table.TableType
import io.kvision.table.cell
import io.kvision.table.row
import io.kvision.table.table
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.DunningComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.DunningComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.DunningLevelDto
import network.lapis.cloud.shared.domain.DunningLevelInput
import network.lapis.cloud.shared.domain.DunningSettingsDto
import network.lapis.cloud.shared.rpc.IDunningService

/**
 * Client-UI wave for GitHub Issue #5. Route ADMIN-only (see `Routes.DUNNING_SETTINGS` KDoc) because
 * `getDunningComplianceDisclaimer` and the level-CRUD methods stay `requireRole(ADMIN)` -- since
 * `f30022c`, `getDunningSettings`/`listDunningLevels` themselves are READ_ROLES (TREASURER/BOARD/
 * ADMIN), but this screen's write actions (enable/disable, level CRUD, disclaimer acknowledgment)
 * still require an admin, so the route as a whole is not opened to TREASURER/BOARD.
 *
 * Structure mirrors `SepaSettingsScreen.kt` -- but [DunningSettingsDto] has NO
 * `lastAcknowledgedByDisplayName` field (unlike `SepaSettingsDto`), so the "last acknowledged"
 * line is deliberately NOT copied verbatim from `renderSepaSettingsSummary`.
 */
fun renderDunningSettingsScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 700.px
            marginTop = 24.px
        }
    root.pageHeader(tr("Mahnwesen-Konfiguration"))

    renderDunningAdminSection(root)
    renderDunningLevelsSection(root)
}

// ================================================================================================
// Status + Aktivieren/Deaktivieren
// ================================================================================================

private fun renderDunningAdminSection(root: SimplePanel) {
    root.h2(tr("Status")) { addCssClass("h5") }
    val settingsPanel = root.vPanel(spacing = 4)
    settingsPanel.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }

    fun loadSettings() {
        settingsPanel.removeAll()
        settingsPanel.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val settings =
                dunningGuarded(tr(DUNNING_READ_CONFLICT_MESSAGE)) {
                    rpcService<IDunningService>().getDunningSettings()
                } ?: return@launch
            settingsPanel.removeAll()
            renderDunningSettingsSummary(settingsPanel, settings)
        }
    }

    val actionsRow = root.hPanel(spacing = 8) { addCssClasses("mt-2") }
    val enableButton = actionsRow.button(tr("Mahnwesen aktivieren …"), style = ButtonStyle.PRIMARY)
    val disableButton = actionsRow.button(tr("Mahnwesen deaktivieren"), style = ButtonStyle.OUTLINEDANGER)

    fun acknowledgeAndEnable() {
        enableButton.disabled = true
        AppScope.launch {
            val disclaimer =
                dunningGuarded(tr(DUNNING_READ_CONFLICT_MESSAGE)) { rpcService<IDunningService>().getDunningComplianceDisclaimer() }
            enableButton.disabled = false
            if (disclaimer != null) {
                dunningEnableDisclaimerModal(disclaimer) {
                    AppScope.launch {
                        val result =
                            dunningGuarded(tr(DUNNING_WRITE_CONFLICT_MESSAGE)) {
                                rpcService<IDunningService>().enableDunning(
                                    DunningComplianceAcknowledgmentInput(
                                        disclaimerVersion = disclaimer.version,
                                        disclaimerSha256 = disclaimer.sha256,
                                    ),
                                )
                            }
                        if (result != null) {
                            notifySuccess(tr("Mahnwesen aktiviert."))
                            loadSettings()
                        }
                    }
                }
            }
        }
    }
    enableButton.onClick { acknowledgeAndEnable() }

    disableButton.onClick {
        dunningDisableConfirmDialog {
            disableButton.disabled = true
            AppScope.launch {
                val result = dunningGuarded(tr(DUNNING_WRITE_CONFLICT_MESSAGE)) { rpcService<IDunningService>().disableDunning() }
                disableButton.disabled = false
                if (result != null) {
                    notifySuccess(tr("Mahnwesen deaktiviert."))
                    loadSettings()
                }
            }
        }
    }

    loadSettings()
}

private fun renderDunningSettingsSummary(
    panel: SimplePanel,
    settings: DunningSettingsDto,
) {
    val statusRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    statusRow.div(tr("Status:")) { addCssClasses("text-muted small") }
    statusRow.statusBadge(
        if (settings.dunningEnabled) tr("Aktiviert") else tr("Deaktiviert"),
        if (settings.dunningEnabled) "success" else "secondary",
    )

    // DunningSettingsDto carries no `lastAcknowledgedByDisplayName` (unlike SepaSettingsDto) --
    // only the version and the timestamp, so this line is deliberately shorter than
    // `renderSepaSettingsSummary`'s equivalent.
    if (settings.lastAcknowledgedAt != null && settings.lastDisclaimerVersion != null) {
        panel.div(
            gettext(
                "Zuletzt bestätigt am %1 (Hinweistext-Version %2).",
                settings.lastAcknowledgedAt,
                settings.lastDisclaimerVersion,
            ),
        ) { addCssClasses("text-muted small") }
    } else {
        panel.div(tr("Noch keine Bestätigung des rechtlichen Hinweistexts erfolgt.")) { addCssClasses("text-muted small") }
    }

    if (DunningAuthzUi.showNoActiveLevelWarning(settings)) {
        val band = panel.div { addCssClasses("alert alert-warning mt-2") }
        band.div(
            tr("Das Mahnwesen ist aktiviert, aber keine Mahnstufe ist konfiguriert -- es wird nichts gemahnt."),
        ) { addCssClass("fw-bold") }
    }

    panel.div(
        gettext("Automatischer Mahnlauf (Poller): %1", if (settings.pollerEnabled) gettext("aktiv") else gettext("inaktiv")),
    ) { addCssClasses("text-muted small") }
    panel.div(
        gettext(
            "Postversand (Umgebung): %1",
            if (settings.postalDispatchEnabled) gettext("aktiv") else gettext("inaktiv"),
        ),
    ) { addCssClasses("text-muted small") }
    panel.div(
        gettext(
            "Postversand (Organisation): %1",
            if (settings.postalMailEnabled) gettext("aktiviert") else gettext("deaktiviert"),
        ),
    ) { addCssClasses("text-muted small") }
    panel.div(gettext("Aktive Mahnstufen: %1", settings.activeLevelCount)) { addCssClasses("text-muted small") }
}

private fun dunningEnableDisclaimerModal(
    disclaimer: DunningComplianceDisclaimerDto,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = gettext("Mahnwesen aktivieren -- rechtlicher Hinweis (Version %1)", disclaimer.version))
    modal.div(
        tr(
            "Bitte lesen Sie den folgenden rechtlichen Hinweistext vollständig, bevor Sie das Mahnwesen aktivieren. " +
                "Diese Plattform führt keine automatisierte Rechtsberatung durch -- die rechtliche Einordnung liegt " +
                "bei Ihrer Organisation.",
        ),
    ) { addCssClasses("text-muted small mb-2") }
    modal.div {
        addCssClasses("border rounded p-2 mb-2")
        maxHeight = 300.px
        overflow = Overflow.AUTO
        content = disclaimer.text
    }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Ich bestätige, den aktuellen Text gelesen zu haben"), style = ButtonStyle.PRIMARY).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

private fun dunningDisableConfirmDialog(onConfirm: () -> Unit) {
    val modal = Modal(caption = tr("Mahnwesen deaktivieren bestätigen"))
    modal.div(
        tr(
            "Der automatische Mahnlauf und alle manuellen Mahnaktionen sind bis zur erneuten Aktivierung nicht " +
                "mehr möglich. Bereits ausgestellte Mahnungen bleiben in der Historie erhalten.",
        ),
    ) { addCssClasses("fw-bold text-danger") }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(
        Button(tr("Deaktivieren"), style = ButtonStyle.DANGER).apply {
            onClick {
                modal.hide()
                onConfirm()
            }
        },
    )
    modal.show()
}

// ================================================================================================
// Mahnstufen
// ================================================================================================

private fun renderDunningLevelsSection(root: SimplePanel) {
    root.h2(tr("Mahnstufen")) { addCssClass("h5") }
    val filterRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val includeInactiveCheck = filterRow.checkBox(label = tr("Inaktive Stufen anzeigen"))
    val listPanel = root.vPanel(spacing = 6)

    fun loadLevels() {
        listPanel.removeAll()
        AppScope.launch {
            val levels =
                dunningGuarded(tr(DUNNING_READ_CONFLICT_MESSAGE)) {
                    rpcService<IDunningService>().listDunningLevels(includeInactive = includeInactiveCheck.value)
                } ?: return@launch
            listPanel.removeAll()
            if (levels.isEmpty()) {
                listPanel.p(tr("Noch keine Mahnstufen konfiguriert."))
                return@launch
            }
            val table =
                listPanel.table(
                    headerNames =
                        listOf(
                            tr("Nr."),
                            tr("Name"),
                            tr("Wartefrist"),
                            tr("Antwortfrist"),
                            tr("Gebühr"),
                            tr("Aktiv"),
                            "",
                        ),
                    types = setOf(TableType.STRIPED, TableType.HOVER),
                )
            levels.sortedBy { it.levelNumber }.forEach { level -> renderDunningLevelRow(table, level, ::loadLevels) }
        }
    }
    includeInactiveCheck.subscribe { loadLevels() }
    loadLevels()

    root.h2(tr("Mahnstufe anlegen")) { addCssClass("h6") }
    renderDunningLevelForm(root, existing = null, onSaved = ::loadLevels)
}

private fun renderDunningLevelRow(
    table: Table,
    level: DunningLevelDto,
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
        val editButton = actionsCell.button(tr("Bearbeiten"), style = ButtonStyle.OUTLINESECONDARY)
        editButton.onClick {
            confirmEditDunningLevel(level, onChanged)
        }
        if (level.active) {
            val deactivateButton = actionsCell.button(tr("Deaktivieren"), style = ButtonStyle.OUTLINEDANGER)
            deactivateButton.onClick {
                confirmDialog(
                    title = tr("Mahnstufe deaktivieren"),
                    message =
                        gettext(
                            "Mahnstufe \"%1 · %2\" wirklich deaktivieren? Bereits ausgestellte Mahnungen bleiben " +
                                "erhalten, die Stufe steht aber für künftige Eskalationen nicht mehr zur Verfügung.",
                            level.levelNumber,
                            level.name,
                        ),
                    confirmLabel = tr("Deaktivieren"),
                ) {
                    AppScope.launch {
                        val result =
                            dunningGuarded(tr(DUNNING_LEVEL_CONFLICT_MESSAGE)) {
                                rpcService<IDunningService>().deactivateDunningLevel(level.id)
                            }
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

/** Opens the add/edit form pre-filled for [level] inside a modal -- kept minimal (reuses
 * [renderDunningLevelForm] directly) rather than a second, parallel edit-form implementation. */
private fun confirmEditDunningLevel(
    level: DunningLevelDto,
    onChanged: () -> Unit,
) {
    val modal = Modal(caption = gettext("Mahnstufe \"%1\" bearbeiten", level.name))
    // Die Primäraktion steht in der Modal-Fußleiste (Abbrechen links, Speichern rechts, R27), nicht im Formularkörper.
    renderDunningLevelForm(modal, existing = level, modal = modal) {
        modal.hide()
        onChanged()
    }
    modal.show()
}

/**
 * Shared add/edit form. Client-side pre-validation in [validateDunningLevelInput] exactly mirrors
 * `DunningService.validateLevelInput` (`DunningService.kt:861-888`) -- every bound named there
 * (levelNumber 1..1000, graceDays/responseDays 1..365, feeAmount 0.00..25.00, name non-blank,
 * no fee on level 1) is re-derived here so a treasurer/admin sees the SAME rejection before the
 * round trip, not just after. The name's max-100-chars bound is deliberately NOT a rejection here
 * either -- the server silently truncates an overlong name (`name.trim().take(MAX_LEVEL_NAME_LENGTH)`,
 * DunningService.kt:884) rather than rejecting it, so this form truncates before validating instead
 * of blocking submission, to stay an exact mirror rather than a stricter gate.
 */
internal fun renderDunningLevelForm(
    root: SimplePanel,
    existing: DunningLevelDto?,
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
            hint = gettext("Zulässig: %1 bis %2.", 1, MAX_DUNNING_LEVEL_NUMBER),
            rule = { FormRules.intInRange(value = it, min = 1, max = MAX_DUNNING_LEVEL_NUMBER) },
        )
    // Der Name wird NICHT abgelehnt, wenn er zu lang ist -- der Server kürzt still (siehe KDoc oben), der Client spiegelt das.
    val nameField = form.textField(label = tr("Name"), value = existing?.name, required = true)
    val graceDaysField =
        form.textField(
            label = tr("Wartefrist in Tagen"),
            value = existing?.graceDays?.toString(),
            required = true,
            hint = gettext("Zulässig: %1 bis %2.", MIN_DUNNING_DAYS, MAX_DUNNING_DAYS),
            rule = { FormRules.intInRange(value = it, min = MIN_DUNNING_DAYS, max = MAX_DUNNING_DAYS) },
        )
    val responseDaysField =
        form.textField(
            label = tr("Antwortfrist in Tagen"),
            value = existing?.responseDays?.toString(),
            required = true,
            hint = gettext("Zulässig: %1 bis %2.", MIN_DUNNING_DAYS, MAX_DUNNING_DAYS),
            rule = { FormRules.intInRange(value = it, min = MIN_DUNNING_DAYS, max = MAX_DUNNING_DAYS) },
        )
    val feeField =
        form.textField(
            label = tr("Gebühr in EUR"),
            value = existing?.feeAmount?.toString(),
            hint = gettext("Höchstens %1.", feeBound(MAX_DUNNING_FEE_AMOUNT)),
            rule = { dunningFeeCheck(it) },
        )
    val levelNumberInput = levelNumberField.control as Text
    val feeInput = feeField.control as Text
    val feeHint =
        form.panel.div().apply {
            addCssClasses("text-muted small")
            content =
                tr(
                    "§ 286 BGB: Eine erste Zahlungserinnerung begründet den Verzug in aller Regel erst -- eine " +
                        "Gebühr ist auf dieser Stufe unzulässig.",
                )
        }
    // Reactivation escape hatch for a deactivated level (finding: `deactivateDunningLevel` has no
    // UI counterpart, and re-creating the level number after deactivation is blocked server-side
    // by the active-agnostic duplicate check in `createDunningLevel`/`updateDunningLevel`). Only
    // shown when editing -- a freshly created level is always active.
    val activeField =
        if (existing != null) {
            form.checkField(value = existing.active, label = tr("Aktiv"))
        } else {
            null
        }
    val submitButton = Button(if (existing == null) tr("Mahnstufe anlegen") else tr("Speichern"), style = ButtonStyle.PRIMARY)
    if (modal != null) {
        // Im Modal steht die Knopfzeile in der Fußleiste: Abbrechen links, bestätigende Aktion rechts (R27).
        form.finish()
        modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
        modal.addButton(submitButton)
    } else {
        form.buttons(primary = submitButton)
    }

    fun updateFeeLock() {
        val isLevelOne = levelNumberInput.value?.trim()?.toIntOrNull() == 1
        if (isLevelOne) {
            // Über das LapisField: ein schon gezeigter Feldfehler darf nicht an einem leeren, deaktivierten Feld
            // stehen bleiben (das Feld ist deaktiviert, der Nutzer könnte ihn nicht mehr wegtippen).
            feeField.setValue(null)
            feeField.clearError()
            feeInput.disabled = true
            feeHint.show()
        } else {
            feeInput.disabled = false
            feeHint.hide()
        }
    }
    levelNumberInput.subscribe { updateFeeLock() }
    updateFeeLock()

    // Derselbe Parser wie die Feldregel [dunningFeeCheck] (und wie das Forderungs-Mahnstufen-Formular): was das Feld absegnet,
    // wird 1:1 gespeichert -- keine stille Rundung ("12,999" -> 13,00), kein "1e1". BEWUSST nicht an `feeInput.disabled`
    // gekoppelt: auf Stufe 1 leert [updateFeeLock] das Feld ohnehin; sähe der Validator hier stets `null`, wäre seine
    // § 286-Regel unerreichbar und die Sperre allein ein disabled-Attribut.
    fun parseFee(): Decimal? = (parseDunningFeeInput(feeField.value) as? AmountInput.Valid)?.value

    // Mirrors the server's own `name.trim().take(MAX_LEVEL_NAME_LENGTH)` (DunningService.kt:884) exactly -- the server
    // silently truncates an overlong name rather than rejecting it, so the client must not reject it either (see
    // [validateDunningLevelInput] KDoc).
    fun trimmedName(): String = nameField.value.trim().take(MAX_DUNNING_LEVEL_NAME_LENGTH)

    // [validateDunningLevelInput] bleibt die Autorität für das Zusammenspiel der Felder -- vor allem die § 286-BGB-Regel "keine
    // Gebühr auf Stufe 1", die der Validator ERZWINGT (die Sperre des Feldes in [updateFeeLock] ist nur die Bedienhilfe
    // davor; ist der Wert dennoch da, z. B. programmatisch gesetzt, lehnt der Validator ab). Er läuft nur, wenn jedes einzelne
    // Feld für sich gültig ist -- sonst stünde dieselbe Beanstandung doppelt da: am Feld UND in der Sammelfläche.
    form.crossFieldRule {
        if (form.fields.all { it.isValid() }) {
            val validationError =
                validateDunningLevelInput(
                    levelNumber = levelNumberField.value.trim().toIntOrNull(),
                    name = trimmedName(),
                    graceDays = graceDaysField.value.trim().toIntOrNull(),
                    responseDays = responseDaysField.value.trim().toIntOrNull(),
                    feeAmount = parseFee(),
                )
            if (validationError != null) FieldCheck.Invalid(validationError) else FieldCheck.Ok
        } else {
            FieldCheck.Ok
        }
    }

    submitButton.onClick {
        form.submit(submitButton) {
            val result =
                dunningGuarded(tr(DUNNING_LEVEL_CONFLICT_MESSAGE)) {
                    // Der Bau des Objekts steht IM Guard: ein Fehler dabei wird gemeldet statt in den AppScope zu entweichen.
                    val input =
                        buildDunningLevelInput(
                            levelNumber = levelNumberField.value,
                            name = nameField.value,
                            graceDays = graceDaysField.value,
                            responseDays = responseDaysField.value,
                            fee = parseFee(),
                            active = (activeField?.control as CheckBox?)?.value ?: true,
                        )
                    if (existing == null) {
                        rpcService<IDunningService>().createDunningLevel(input)
                    } else {
                        rpcService<IDunningService>().updateDunningLevel(existing.id, input)
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

/**
 * Baut die Anfrage aus den ROHEN Feldwerten (getrimmt; der Name auf die Servergrenze gekürzt, siehe [renderDunningLevelForm]).
 * Vier `Int`-Felder und ein Name sind sonst beliebig vertauschbar -- deshalb eine eigene, einzeln testbare Funktion.
 */
internal fun buildDunningLevelInput(
    levelNumber: String,
    name: String,
    graceDays: String,
    responseDays: String,
    fee: Decimal?,
    active: Boolean,
): DunningLevelInput =
    DunningLevelInput(
        levelNumber = levelNumber.trim().toInt(),
        name = name.trim().take(MAX_DUNNING_LEVEL_NAME_LENGTH),
        graceDays = graceDays.trim().toInt(),
        responseDays = responseDays.trim().toInt(),
        feeAmount = fee,
        active = active,
    )

/**
 * Betrags-Parser der Gebühr: [parseAmountInput] (Dezimalkomma ODER -punkt, höchstens zwei Nachkommastellen, kein Exponent --
 * dieselbe Grammatik wie im Forderungs-Mahnstufen-Formular), aber mit `0` als gültigem Betrag (der Server erlaubt 0,00 bis
 * 25,00 EUR, das Feld ist optional, während Buchungsbeträge strikt größer 0 sein müssen) und ohne die Milliarden-Grenze des
 * Buchungsbetrags (die eigene Obergrenze prüft [dunningFeeCheck]).
 */
internal fun parseDunningFeeInput(raw: String?): AmountInput = parseAmountInput(raw, allowZero = true, enforceMaxAmount = false)

/** Feldregel der Gebühr: ein gültiger Betrag zwischen 0,00 und 25,00 EUR (der Server bleibt Autorität). */
internal fun dunningFeeCheck(text: String): FieldCheck = FormRules.optionalFee(text, MAX_DUNNING_FEE_AMOUNT)

private const val MAX_DUNNING_FEE_AMOUNT = 25.0

private const val MAX_DUNNING_LEVEL_NUMBER = 1000
private const val MAX_DUNNING_LEVEL_NAME_LENGTH = 100
private const val MIN_DUNNING_DAYS = 1
private const val MAX_DUNNING_DAYS = 365

/**
 * Pure, DOM-free client-side pre-validation -- exact mirror of
 * `DunningService.validateLevelInput` (`DunningService.kt:861-888`), including the "no fee on
 * level 1" rule (§ 286 BGB) and the same bounds for every field. Directly unit-tested (see
 * `DunningLevelValidationTest.kt`), same "loose mirror, not the security boundary" posture every
 * other client-side validator in [Validation] documents -- the server remains the authority.
 */
internal fun validateDunningLevelInput(
    levelNumber: Int?,
    name: String,
    graceDays: Int?,
    responseDays: Int?,
    feeAmount: Decimal?,
): String? {
    if (levelNumber == null || levelNumber !in 1..MAX_DUNNING_LEVEL_NUMBER) {
        return gettext("Die Stufennummer muss zwischen 1 und %1 liegen.", MAX_DUNNING_LEVEL_NUMBER)
    }
    if (graceDays == null || graceDays !in MIN_DUNNING_DAYS..MAX_DUNNING_DAYS) {
        return gettext("Die Wartefrist muss zwischen %1 und %2 Tagen liegen.", MIN_DUNNING_DAYS, MAX_DUNNING_DAYS)
    }
    if (responseDays == null || responseDays !in MIN_DUNNING_DAYS..MAX_DUNNING_DAYS) {
        return gettext("Die Antwortfrist muss zwischen %1 und %2 Tagen liegen.", MIN_DUNNING_DAYS, MAX_DUNNING_DAYS)
    }
    if (feeAmount != null) {
        val feeDouble = feeAmount.toDouble()
        if (feeDouble < 0.0 || feeDouble > 25.0) {
            return feeRangeMessage(MAX_DUNNING_FEE_AMOUNT)
        }
    }
    if (feeAmount != null && levelNumber == 1) {
        return tr(
            "Eine Mahngebühr auf der ersten Mahnstufe ist unzulässig -- eine erste Zahlungserinnerung begründet " +
                "den Verzug (§ 286 BGB) in aller Regel erst.",
        )
    }
    if (name.isBlank()) {
        return tr("Der Name darf nicht leer sein.")
    }
    return null
}
