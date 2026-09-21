package network.lapis.cloud.client

import io.kvision.core.Overflow
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.SepaComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.SepaComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.SepaCreditorSettingsDto
import network.lapis.cloud.shared.domain.SepaCreditorSettingsInput
import network.lapis.cloud.shared.domain.SepaSettingsDto
import network.lapis.cloud.shared.rpc.ISepaService

/**
 * V1.2.2 SEPA-Client-UI wave -- Plan §2.8/§4.4. ADMIN-only (see `Routes.SEPA_SETTINGS` KDoc).
 * Structure mirrors `AuctionScreen.renderAdminSection`/`renderAuctionSettingsSummary`/
 * `auctionEnableDisclaimerModal` exactly -- including the load-bearing property that `version`/
 * `sha256` are LOCAL values read straight off the just-fetched [SepaComplianceDisclaimerDto], never
 * rendered as editable form fields and never re-derived.
 */
fun renderSepaSettingsScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 700.px
            marginTop = 24.px
        }
    root.pageHeader(tr("SEPA-Konfiguration"))

    renderSepaAdminSection(root)
    renderSepaCreditorSettingsSection(root)
}

// ================================================================================================
// Aktivieren/Deaktivieren
// ================================================================================================

private fun renderSepaAdminSection(root: SimplePanel) {
    root.h2(tr("Status")) { addCssClass("h5") }
    val settingsPanel = root.vPanel(spacing = 4)
    settingsPanel.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }

    fun loadSettings() {
        settingsPanel.removeAll()
        settingsPanel.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
        AppScope.launch {
            val settings = guarded { rpcService<ISepaService>().getSepaSettings() } ?: return@launch
            settingsPanel.removeAll()
            renderSepaSettingsSummary(settingsPanel, settings)
        }
    }

    val actionsRow = root.hPanel(spacing = 8) { addCssClasses("mt-2") }
    val enableButton = actionsRow.button(tr("SEPA-Lastschrift aktivieren …"), style = ButtonStyle.PRIMARY)
    val disableButton = actionsRow.button(tr("SEPA-Lastschrift deaktivieren"), style = ButtonStyle.OUTLINEDANGER)

    fun acknowledgeAndEnable() {
        enableButton.disabled = true
        AppScope.launch {
            val disclaimer = guarded { rpcService<ISepaService>().getSepaComplianceDisclaimer() }
            enableButton.disabled = false
            if (disclaimer != null) {
                sepaEnableDisclaimerModal(disclaimer) {
                    AppScope.launch {
                        val result =
                            guarded {
                                rpcService<ISepaService>().enableSepaDebit(
                                    SepaComplianceAcknowledgmentInput(
                                        disclaimerVersion = disclaimer.version,
                                        disclaimerSha256 = disclaimer.sha256,
                                    ),
                                )
                            }
                        if (result != null) {
                            notifySuccess(tr("SEPA-Lastschrift aktiviert."))
                            loadSettings()
                        }
                    }
                }
            }
        }
    }
    enableButton.onClick { acknowledgeAndEnable() }

    disableButton.onClick {
        sepaDisableConfirmDialog {
            disableButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<ISepaService>().disableSepaDebit() }
                disableButton.disabled = false
                if (result != null) {
                    notifySuccess(tr("SEPA-Lastschrift deaktiviert."))
                    loadSettings()
                }
            }
        }
    }

    loadSettings()
}

private fun renderSepaSettingsSummary(
    panel: SimplePanel,
    settings: SepaSettingsDto,
) {
    val statusRow = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    statusRow.div(tr("Status:")) { addCssClasses("text-muted small") }
    statusRow.statusBadge(
        if (settings.sepaDebitEnabled) tr("Aktiviert") else tr("Deaktiviert"),
        if (settings.sepaDebitEnabled) "success" else "secondary",
    )
    if (settings.lastAcknowledgedByDisplayName != null) {
        panel.div(
            gettext(
                "Zuletzt bestätigt von %1 am %2 (Hinweistext-Version %3).",
                settings.lastAcknowledgedByDisplayName,
                settings.lastAcknowledgedAt,
                settings.lastDisclaimerVersion,
            ),
        ) { addCssClasses("text-muted small") }
    } else {
        panel.div(tr("Noch keine Bestätigung des rechtlichen Hinweistexts erfolgt.")) { addCssClasses("text-muted small") }
    }
}

private fun sepaEnableDisclaimerModal(
    disclaimer: SepaComplianceDisclaimerDto,
    onConfirm: () -> Unit,
) {
    val modal = Modal(caption = gettext("SEPA-Lastschrift aktivieren -- rechtlicher Hinweis (Version %1)", disclaimer.version))
    modal.div(
        tr(
            "Bitte lesen Sie den folgenden rechtlichen Hinweistext vollständig, bevor Sie SEPA-Lastschrift aktivieren. " +
                "Diese Plattform führt keine automatisierte Rechtsberatung durch -- die rechtliche Einordnung liegt bei " +
                "Ihrer Organisation.",
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

private fun sepaDisableConfirmDialog(onConfirm: () -> Unit) {
    val modal = Modal(caption = tr("SEPA-Lastschrift deaktivieren bestätigen"))
    modal.div(
        tr(
            "Neue Mandate, Vorschauen und Lastschriftläufe können bis zur erneuten Aktivierung nicht mehr angelegt " +
                "werden. Bereits erteilte Mandate bleiben bestehen, können von ihren Inhabern aber nicht mehr selbst " +
                "widerrufen werden, solange die Funktion deaktiviert ist (die Beitragsseite zeigt in diesem Zustand " +
                "keine Mandatsverwaltung mehr an).",
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
// Gläubigereinstellungen
// ================================================================================================

/** Server-Grenze der Vorabankündigungsfrist (Tage), loser Spiegel -- der Server bleibt Autorität. */
private const val PRENOTIFICATION_DAYS_MIN: Int = 1
private const val PRENOTIFICATION_DAYS_MAX: Int = 30

/**
 * Baut die Anfrage aus den ROHEN Feldwerten. `sepaCreditorId` und `sepaCreditorName` sind BEIDE `String?` (leer = nicht
 * gesetzt) und damit für den Compiler vertauschbar -- deshalb eine eigene, einzeln testbare Funktion.
 */
internal fun buildSepaCreditorSettingsInput(
    creditorId: String,
    creditorName: String,
    prenotificationDays: String,
): SepaCreditorSettingsInput =
    SepaCreditorSettingsInput(
        sepaCreditorId = creditorId.trim().takeIf { it.isNotBlank() },
        sepaCreditorName = creditorName.trim().takeIf { it.isNotBlank() },
        sepaPrenotificationDays = prenotificationDays.trim().toInt(),
    )

private fun renderSepaCreditorSettingsSection(root: SimplePanel) {
    root.h2(tr("Gläubigereinstellungen")) { addCssClass("h5") }
    // Formular-Grammatik (V1.4.28): Gläubiger-ID/-Name optional, die Frist Pflicht => Fall (a), Stern nur an der Frist.
    val form = root.lapisForm()
    val creditorIdField = form.textField(label = tr("Gläubiger-Identifikationsnummer"))
    val creditorNameField = form.textField(label = tr("Gläubigername"))
    val prenotificationDaysField =
        form.textField(
            label = tr("Vorabankündigungsfrist in Tagen"),
            required = true,
            hint = gettext("Zulässig: %1 bis %2.", PRENOTIFICATION_DAYS_MIN, PRENOTIFICATION_DAYS_MAX),
            rule = { FormRules.intInRange(value = it, min = PRENOTIFICATION_DAYS_MIN, max = PRENOTIFICATION_DAYS_MAX) },
        )
    val readyRow = form.panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    readyRow.div(tr("Bereit für Dateierzeugung:")) { addCssClasses("text-muted small") }
    val readyBadgeHost = readyRow.div()
    val saveButton = Button(tr("Speichern"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = saveButton)

    fun renderReady(ready: Boolean) {
        readyBadgeHost.removeAll()
        readyBadgeHost.statusBadge(if (ready) tr("Ja") else tr("Nein"), if (ready) "success" else "secondary")
    }

    fun applySettings(settings: SepaCreditorSettingsDto) {
        creditorIdField.setValue(settings.sepaCreditorId)
        creditorNameField.setValue(settings.sepaCreditorName)
        prenotificationDaysField.setValue(settings.sepaPrenotificationDays.toString())
        // Programmatisches Schreiben ist kein Tippen (siehe LapisField.setValue): ein schon angezeigter Fehler (Pflicht/Bereich
        // aus einem früheren, ungültigen Versuch) darf nicht am nun gespeicherten, gültigen Wert stehen bleiben.
        form.fields.forEach { it.validate(force = false) }
        renderReady(settings.readyForFileGeneration)
    }

    fun load() {
        AppScope.launch {
            val settings = guarded { rpcService<ISepaService>().getSepaCreditorSettings() } ?: return@launch
            applySettings(settings)
        }
    }

    saveButton.onClick {
        form.submit(saveButton) {
            val result =
                guarded {
                    rpcService<ISepaService>().updateSepaCreditorSettings(
                        buildSepaCreditorSettingsInput(
                            creditorId = creditorIdField.value,
                            creditorName = creditorNameField.value,
                            // Die Feldregel hat die Zahl bereits geprüft.
                            prenotificationDays = prenotificationDaysField.value,
                        ),
                    )
                }
            if (result != null) {
                notifySuccess(tr("Gläubigereinstellungen gespeichert."))
                applySettings(result)
            }
        }
    }

    load()
}
