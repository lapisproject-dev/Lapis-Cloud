package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import network.lapis.cloud.shared.domain.CarpoolPostingDto
import network.lapis.cloud.shared.domain.CarpoolPostingInput
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.rpc.ICarpoolService

/**
 * Welle V1.9.12 "Mitfahrerzentrale" -- "Meine Einträge" (auch knapp abgelaufene, siehe
 * [ICarpoolService.listMyPostings] KDoc), Feed (nur zukünftige, mit Typfilter) und ein
 * Erstell-/Bearbeiten-Formular (inline, kein Modal -- sieben Felder passen bequem in die Seite).
 * Kontaktaufnahme läuft über [ICarpoolService.contactAuthor] -- kein eigenes
 * Carpool-Nachrichtensystem, die Antwort landet im ganz normalen Postfach unter Kommunikation
 * (siehe `CommunicationScreen.renderInbox`).
 *
 * `fromPlace`/`toPlace`/`notes`/`authorDisplayName` sind Freitext eines anderen Mitglieds -- jede
 * zusammengesetzte Anzeige wird ALS GANZES sanitisiert, unmittelbar bevor sie Widget-Inhalt wird
 * ([untrustedDiv]/[untrustedCardTitle]/[sanitizeUntrustedI18nText]), nie roh an `div(...)`/
 * `gettext(...)`-Ergebnisse ohne diesen Schritt übergeben.
 */
fun renderCarpoolScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 640.px
            marginTop = 24.px
        }
    root.pageHeader(tr("Mitfahrerzentrale"))
    root.div(
        tr("Keine Straße, keine Hausnummer -- den genauen Treffpunkt vereinbaren Sie per Nachricht."),
    ) { addCssClasses("text-muted small") }

    val formHost = root.vPanel(spacing = 6)
    val createButton = root.button(tr("Eintrag erstellen"), style = ButtonStyle.PRIMARY)

    // W5-Muster (R34): Laden/Fehler-mit-Wiederholen/leer sind eigene Zustände EINES dataSection --
    // kein manuelles removeAll()/AppScope.launch{guarded{...}} mehr.
    root.h2(tr("Meine Einträge")) { addCssClass("h5") }
    lateinit var myPostingsSection: DataSection
    myPostingsSection =
        root.dataSection<List<CarpoolPostingDto>>(
            emptyText = tr("Noch keine eigenen Einträge."),
            isEmpty = { it.isEmpty() },
            load = { guarded { rpcService<ICarpoolService>().listMyPostings() } },
            render = { panel, postings ->
                postings.forEach { posting ->
                    renderCarpoolCard(panel = panel, posting = posting, formHost = formHost, trigger = createButton)
                }
            },
        )

    val filterRow = root.hPanel(spacing = 8) { addCssClass("align-items-end") }
    val typeFilter =
        filterRow.select(
            options =
                listOf(
                    "" to tr("Alle"),
                    CarpoolPostingType.OFFER.name to tr("Angebote"),
                    CarpoolPostingType.REQUEST.name to tr("Gesuche"),
                ),
            value = "",
            label = tr("Art"),
        )

    root.h2(tr("Fahrten")) { addCssClass("h5") }
    lateinit var feedSection: DataSection
    feedSection =
        root.dataSection<List<CarpoolPostingDto>>(
            emptyText = tr("Noch keine Fahrten eingetragen. Bieten Sie die erste an."),
            filterTerm = { typeFilter.value?.takeIf { it.isNotBlank() }?.let { carpoolTypeFilterLabel(it) } },
            noMatchText = { term -> gettext("Keine Fahrten der Art \"%1\".", term) },
            isEmpty = { it.isEmpty() },
            load = {
                val type = typeFilter.value?.takeIf { it.isNotBlank() }?.let { CarpoolPostingType.valueOf(it) }
                guarded { rpcService<ICarpoolService>().listPostings(type) }
            },
            render = { panel, postings ->
                postings.forEach { posting ->
                    renderCarpoolCard(panel = panel, posting = posting, formHost = formHost, trigger = createButton)
                }
            },
        )

    fun refreshAll() {
        myPostingsSection.reload()
        feedSection.reload()
    }
    carpoolRefreshHooks[formHost] = ::refreshAll

    typeFilter.subscribe { feedSection.reload() }

    createButton.onClick {
        openCarpoolForm(formHost = formHost, trigger = createButton, editing = null, duplicateFrom = null, onSaved = ::refreshAll)
    }

    refreshAll()
}

/** [tr]/[gettext] label for the currently selected feed type filter -- used as [DataSection]'s `filterTerm`/`noMatchText`. */
private fun carpoolTypeFilterLabel(rawTypeValue: String): String =
    when (CarpoolPostingType.valueOf(rawTypeValue)) {
        CarpoolPostingType.OFFER -> tr("Angebote")
        CarpoolPostingType.REQUEST -> tr("Gesuche")
    }

/**
 * [formHost]/[trigger] sind ein einziges, geteiltes Formular-Ziel oben auf der Seite (kein
 * Modal): jede Karte (eigen oder fremd) ruft für "Bearbeiten"/"Duplizieren" dieselbe Instanz auf,
 * damit nie zwei Formulare gleichzeitig offen stehen. Über eine kleine Registry ([carpoolRefreshHooks])
 * erreicht [openCarpoolForm] den `refreshAll`-Aufrufer von [renderCarpoolScreen], ohne dass jede
 * Karte ihre eigene Kopie der beiden Refresh-Closures mitschleppen muss.
 */
private val carpoolRefreshHooks = HashMap<SimplePanel, () -> Unit>()

private fun renderCarpoolCard(
    panel: SimplePanel,
    posting: CarpoolPostingDto,
    formHost: SimplePanel,
    trigger: Button,
) {
    val onSaved = carpoolRefreshHooks[formHost] ?: {}
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClass("align-items-center") }
    headerRow.typeBadge(
        if (posting.type == CarpoolPostingType.OFFER) tr("Angebot") else tr("Gesuch"),
        if (posting.type == CarpoolPostingType.OFFER) "success" else "primary",
    )
    headerRow.untrustedCardTitle(gettext("%1 → %2", posting.fromPlace, posting.toPlace))
    if (posting.isOwn) headerRow.typeBadge(tr("Eigener Eintrag"), "secondary")
    if (posting.isPast) headerRow.statusBadge(tr("Abgelaufen"), "secondary")

    val timeSuffix =
        posting.departureTime?.let { gettext(", ab %1 Uhr", it.toString()) } ?: tr(", Uhrzeit flexibel")
    row.div(formatDate(posting.departureDate) + timeSuffix) { addCssClasses("text-muted small") }

    posting.seatsOffered?.let { seats -> row.div(gettext("%1 freie Plätze", seats)) }
    posting.notes?.takeIf { it.isNotBlank() }?.let { notes -> row.untrustedDiv(notes) { addCssClasses("small") } }
    row.untrustedDiv(gettext("von %1", posting.authorDisplayName)) { addCssClasses("text-muted small") }

    val actionRow = row.hPanel(spacing = 8) { addCssClass("mt-1") }
    if (posting.isOwn) {
        actionRow.button(tr("Bearbeiten"), style = ButtonStyle.OUTLINESECONDARY).onClick {
            openCarpoolForm(formHost = formHost, trigger = trigger, editing = posting, duplicateFrom = null, onSaved = onSaved)
        }
        actionRow.button(tr("Duplizieren"), style = ButtonStyle.OUTLINESECONDARY).onClick {
            openCarpoolForm(formHost = formHost, trigger = trigger, editing = null, duplicateFrom = posting, onSaved = onSaved)
        }
        renderCarpoolDeleteControl(actionRow = actionRow, posting = posting, onSaved = onSaved)
    } else if (!posting.isPast) {
        var contactPanel: SimplePanel? = null
        actionRow.button(tr("Kontakt aufnehmen"), style = ButtonStyle.PRIMARY).onClick {
            if (contactPanel != null) return@onClick
            contactPanel = renderCarpoolContactForm(row = row, posting = posting) { contactPanel = null }
        }
    }
}

/** InlineConfirmGate-Riegel gegen einen doppelten Löschvorgang (Forstall-Ruling: Bestätigung inline, kein zweites Modal). */
private fun renderCarpoolDeleteControl(
    actionRow: SimplePanel,
    posting: CarpoolPostingDto,
    onSaved: () -> Unit,
) {
    val gate = InlineConfirmGate()
    val deleteButton = actionRow.button(tr("Löschen"), style = ButtonStyle.OUTLINEDANGER)
    val confirmBox = actionRow.hPanel(spacing = 8) { hide() }

    fun syncButton() {
        deleteButton.disabled = gate.blocked
    }

    deleteButton.onClick {
        if (!gate.openConfirmation()) return@onClick
        syncButton()
        confirmBox.removeAll()
        confirmBox.div(tr("Wirklich löschen?")) { addCssClasses("align-self-center") }
        confirmBox.button(tr("Zurück"), style = ButtonStyle.SECONDARY).onClick {
            if (!gate.cancelConfirmation()) return@onClick
            confirmBox.hide()
            syncButton()
        }
        val finalButton = confirmBox.button(tr("Endgültig löschen"), style = ButtonStyle.DANGER)
        finalButton.onClick {
            // S4/N5 (1:1 `OpenItemDialogs.kt`s Muster): der Riegel deckt den äußeren Knopf ab,
            // `runGuardedAction` sperrt zusätzlich den inneren -- und macht den Schreibzugriff für
            // den R29-Scanner als geschützt erkennbar (kein rohes `AppScope.launch{...write...}`).
            if (!gate.beginRequest()) return@onClick
            syncButton()
            runGuardedAction(finalButton) {
                var succeeded = false
                try {
                    val result = guarded { rpcService<ICarpoolService>().deletePosting(posting.id) }
                    if (result != null) {
                        succeeded = true
                        notifySuccess(tr("Eintrag wurde gelöscht."))
                        onSaved()
                    } else {
                        confirmBox.show()
                    }
                } finally {
                    gate.endRequest(succeeded)
                    syncButton()
                }
            }
        }
        confirmBox.show()
    }
}

private fun renderCarpoolContactForm(
    row: SimplePanel,
    posting: CarpoolPostingDto,
    onClosed: () -> Unit,
): SimplePanel {
    val panel = row.vPanel(spacing = 6) { addCssClasses("border-top pt-2 mt-1") }
    val form = panel.lapisForm()
    val prefill =
        gettext(
            "Hallo, ich interessiere mich für Ihre Fahrt %1 → %2 am %3.",
            posting.fromPlace,
            posting.toPlace,
            formatDate(posting.departureDate),
        )
    val messageField = form.textAreaField(label = tr("Nachricht"), rows = 3, value = sanitizeUntrustedI18nText(prefill), required = true)
    val sendButton = Button(tr("Senden"), style = ButtonStyle.PRIMARY)
    val cancelButton = Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY)
    form.buttons(primary = sendButton, cancel = cancelButton)
    cancelButton.onClick {
        row.remove(panel)
        onClosed()
    }
    sendButton.onClick {
        form.submit(sendButton) {
            val result = guarded { rpcService<ICarpoolService>().contactAuthor(posting.id, messageField.value.trim()) }
            if (result != null) {
                notifySuccess(tr("Nachricht wurde gesendet. Antworten finden Sie im Postfach unter Kommunikation."))
                row.remove(panel)
                onClosed()
            }
        }
    }
    return panel
}

/**
 * A native `<input type="time">` yields `"HH:mm"` (no seconds) -- kotlinx-datetime's
 * [LocalTime.parse] default ISO format requires seconds, so a bare `"HH:mm"` is normalized to
 * `"HH:mm:00"` first. `"HH:mm:ss"` (already has seconds) is passed through unchanged.
 */
internal fun parseTimeInputValue(raw: String): LocalTime? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val normalized = if (trimmed.count { it == ':' } == 1) "$trimmed:00" else trimmed
    return runCatching { LocalTime.parse(normalized) }.getOrNull()
}

private fun openCarpoolForm(
    formHost: SimplePanel,
    trigger: Button,
    editing: CarpoolPostingDto?,
    duplicateFrom: CarpoolPostingDto?,
    onSaved: () -> Unit,
) {
    formHost.removeAll()
    trigger.hide()
    val base = duplicateFrom ?: editing
    val panel = formHost.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    panel.div(if (editing != null) tr("Eintrag bearbeiten") else tr("Neuer Eintrag")) { addCssClass("fw-bold") }
    val form = panel.lapisForm()

    val typeField =
        form.selectField(
            label = tr("Art"),
            options =
                listOf(
                    CarpoolPostingType.OFFER.name to tr("Ich biete eine Fahrt an"),
                    CarpoolPostingType.REQUEST.name to tr("Ich suche eine Fahrt"),
                ),
            value = (base?.type ?: CarpoolPostingType.OFFER).name,
            required = true,
        )
    val fromField = form.textField(label = tr("Von (Ort oder PLZ)"), value = base?.fromPlace, required = true)
    val toField = form.textField(label = tr("Nach (Ort oder PLZ)"), value = base?.toPlace, required = true)
    val dateField =
        form.textField(
            label = tr("Abfahrtsdatum"),
            type = InputType.DATE,
            value = if (duplicateFrom != null) null else base?.departureDate?.toString(),
            required = true,
            requiredMessage = tr("Bitte ein gültiges Datum angeben."),
            rule = { FormRules.isoDate(it) },
        )
    val timeField =
        form.textField(
            label = tr("Abfahrtszeit (optional)"),
            type = InputType.TIME,
            value = if (duplicateFrom != null) null else base?.departureTime?.toString(),
        )
    val seatsField =
        form.textField(
            label = tr("Freie Plätze"),
            type = InputType.NUMBER,
            value = base?.seatsOffered?.toString(),
            hint = tr("Nur bei einem Angebot -- 1 bis 8 Plätze."),
        )
    val notesField = form.textAreaField(label = tr("Notiz (optional)"), rows = 3, value = base?.notes)

    fun syncSeatsVisibility() {
        seatsField.setVisible(typeField.value == CarpoolPostingType.OFFER.name)
    }
    syncSeatsVisibility()
    typeField.subscribe { syncSeatsVisibility() }

    val saveButton = Button(tr("Speichern"), style = ButtonStyle.PRIMARY)
    val cancelButton = Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY)
    form.buttons(primary = saveButton, cancel = cancelButton)

    fun closeForm() {
        formHost.removeAll()
        trigger.show()
    }
    cancelButton.onClick { closeForm() }

    saveButton.onClick {
        form.submit(saveButton) submit@{
            val type = CarpoolPostingType.valueOf(typeField.value.orEmpty())
            val seatsText = seatsField.value.trim()
            if (type == CarpoolPostingType.OFFER) {
                val seats = seatsText.toIntOrNull()
                if (seats == null || seats !in 1..8) {
                    seatsField.showError(tr("Bitte die Anzahl freier Plätze angeben (1 bis 8)."))
                    return@submit
                }
            }
            val input =
                CarpoolPostingInput(
                    type = type,
                    fromPlace = fromField.value.trim(),
                    toPlace = toField.value.trim(),
                    departureDate = LocalDate.parse(dateField.value.trim()),
                    departureTime = parseTimeInputValue(timeField.value),
                    seatsOffered = if (type == CarpoolPostingType.OFFER) seatsText.toInt() else null,
                    notes = notesField.value.trim().takeIf { it.isNotEmpty() },
                )
            val result =
                guarded {
                    if (editing != null) {
                        rpcService<ICarpoolService>().updatePosting(editing.id, input)
                    } else {
                        rpcService<ICarpoolService>().createPosting(input)
                    }
                }
            if (result != null) {
                notifySuccess(if (editing != null) tr("Eintrag wurde aktualisiert.") else tr("Eintrag wurde erstellt."))
                closeForm()
                onSaved()
            }
        }
    }
}
