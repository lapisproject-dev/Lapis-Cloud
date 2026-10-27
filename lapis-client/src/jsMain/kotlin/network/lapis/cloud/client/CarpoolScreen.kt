package network.lapis.cloud.client

import io.kvision.form.check.checkBox
import io.kvision.form.select.select
import io.kvision.html.ButtonStyle
import io.kvision.html.InputType
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.datetime.LocalTime
import network.lapis.cloud.shared.domain.CarpoolMyParticipationDto
import network.lapis.cloud.shared.domain.CarpoolPostingDto
import network.lapis.cloud.shared.domain.CarpoolPostingInput
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ICarpoolService

/**
 * Welle V1.9.12 "Mitfahrzentrale" -- EINE Liste "Fahrten" (seit V1.9.186; vorher zusätzlich "Meine Einträge"): Feed (nur zukünftige, mit
 * Typfilter) oder, mit dem Schalter "Nur meine", die eigenen Einträge (auch knapp abgelaufene, siehe
 * [ICarpoolService.listMyPostings] KDoc) -- und ein
 * Erstell-/Bearbeiten-Formular (seit V1.9.50 das eingeklappte Erstell-Formular der Titelzeile, R36B
 * -- "Bearbeiten"/"Duplizieren" öffnen dasselbe, vorbefüllte Formular; sieben Felder passen bequem in die Seite).
 * Kontaktaufnahme läuft über [ICarpoolService.contactAuthor] -- kein eigenes
 * Carpool-Nachrichtensystem, die Antwort landet im ganz normalen Posteingang unter Nachrichten
 * (siehe `MessagesScreen`).
 *
 * **V1.9.125 "Mitfahrten"**: ACTIVE, GUEST und FRIEND sehen diese Seite (die Passagier-Allowlist von [ICarpoolService]). Mitglieder behalten
 * alles oben Beschriebene und bekommen "Meine Mitfahrten" sowie die Mitfahrerverwaltung an eigenen Angeboten; Gäste und Freunde sehen nur
 * die Angebote, können eine Mitfahrt anfragen bzw. eine Einladung beantworten -- ohne "Eintrag erstellen", ohne Art-Filter, ohne
 * Kontaktformular (Direktnachrichten gibt es für sie nicht). Eine Live-Region (`role="status"`) meldet jede Statusänderung.
 *
 * `fromPlace`/`toPlace`/`notes`/`authorDisplayName` sind Freitext eines anderen Mitglieds -- jede
 * zusammengesetzte Anzeige wird ALS GANZES sanitisiert, unmittelbar bevor sie Widget-Inhalt wird
 * ([untrustedDiv]/[untrustedCardTitle]/[sanitizeUntrustedI18nText]), nie roh an `div(...)`/
 * `gettext(...)`-Ergebnisse ohne diesen Schritt übergeben.
 */
fun renderCarpoolScreen(container: SimplePanel) {
    val root =
        container.pageShell(PageWidth.WIDE, spacing = 14)
    val header = root.pageHeader(tr("Mitfahrzentrale"), intro = PageIntros.forRoute(Routes.CARPOOL))
    // `requireAuth` guarantees a session; without one (a bare test mount) the pre-V1.9.125 member view is kept -- the server gates anyway.
    val isMember = NavVisibility.mayOfferCarpool(AppState.session?.status ?: MemberStatus.ACTIVE)
    if (isMember) {
        root.div(
            tr("Keine Straße, keine Hausnummer -- den genauen Treffpunkt vereinbaren Sie per Nachricht."),
        ) { addCssClasses("text-muted small") }
    } else {
        root.div(
            tr("Als Gast können Sie bei angebotenen Fahrten mitfahren. Fahrten anbieten können nur Mitglieder."),
        ) { addCssClasses("text-muted small") }
        root.div(
            tr("Den Treffpunkt nennt Ihnen der Fahrer, sobald die Mitfahrt bestätigt ist."),
        ) { addCssClasses("text-muted small") }
    }
    // Polite live region: every status change ("Mitfahrt bestätigt.") is announced to screen readers. Mounted empty, text changes only.
    val liveRegion =
        root.div("") {
            addCssClass("visually-hidden")
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
        }

    // V1.9.50 (R36B): the create entry is the collapsible create form of the page header (one button in the title row); "Bearbeiten" and
    // "Duplizieren" of a card open the SAME host, pre-filled. Built once; the sections below read the controller only when they render.
    val formHost = root.vPanel(spacing = 6)
    lateinit var formController: CollapsibleCreateFormController<CarpoolFormPrefill>
    lateinit var refreshAll: () -> Unit
    val ui = CarpoolUi(announce = { liveRegion.content = it }, onChanged = { refreshAll() })

    // V1.9.125 "Meine Mitfahrten": the rides of the person as a passenger, waiting items first. The whole block stays hidden while there is
    // nothing to show (an error keeps it visible, so the retry button is reachable).
    val myRidesBlock = root.vPanel(spacing = 6)
    myRidesBlock.h2(tr("Meine Mitfahrten")) { addCssClass("h5") }
    val myRidesSection =
        myRidesBlock.dataSection<List<CarpoolMyParticipationDto>>(
            emptyText = "",
            isEmpty = { it.isEmpty() },
            onSettled = { items -> myRidesBlock.visible = items == null || items.isNotEmpty() },
            load = { guarded { rpcService<ICarpoolService>().listMyParticipations() } },
            render = { panel, items -> items.forEach { renderMyParticipationRow(panel, it, ui) } },
        )

    // V1.9.186: ONE list. Members get a toolbar above it: "Nur meine" switches the source to their own entries (expired ones included), the
    // "Art" select narrows either view. Both are filters, never submitted (R24B FILTER_IS_NOT_A_FORM). Guests get no toolbar.
    var typeFilter: io.kvision.form.select.Select? = null
    var onlyMineCheck: io.kvision.form.check.CheckBox? = null
    if (isMember) {
        val filterRow = root.lapisToolbar()
        onlyMineCheck = filterRow.checkBox(label = tr("Nur meine"))
        typeFilter =
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
    }

    root.h2(tr("Fahrten")) { addCssClass("h5") }
    lateinit var feedSection: DataSection
    feedSection =
        root.dataSection<List<CarpoolPostingDto>>(
            emptyText =
                if (isMember) {
                    tr(
                        "Noch keine Fahrten eingetragen. Bieten Sie die erste an.",
                    )
                } else {
                    tr("Noch keine Fahrten angeboten.")
                },
            filterTerm = {
                // An active type filter names itself; "Nur meine" without a type filter is carried by a sentinel the noMatchText resolves.
                typeFilter?.value?.takeIf { it.isNotBlank() }?.let { carpoolTypeFilterLabel(it) }
                    ?: if (onlyMineCheck?.value == true) CARPOOL_ONLY_MINE_TERM else null
            },
            noMatchText = { term ->
                if (term ==
                    CARPOOL_ONLY_MINE_TERM
                ) {
                    gettext("Noch keine eigenen Einträge.")
                } else {
                    gettext("Keine Fahrten der Art \"%1\".", term)
                }
            },
            isEmpty = { it.isEmpty() },
            load = {
                val type = typeFilter?.value?.takeIf { it.isNotBlank() }?.let { CarpoolPostingType.valueOf(it) }
                if (onlyMineCheck?.value == true) {
                    // `listMyPostings` takes no type: the narrowing happens here, on the (small, capped) list of the own entries.
                    guarded { rpcService<ICarpoolService>().listMyPostings() }?.filter { type == null || it.type == type }
                } else {
                    guarded { rpcService<ICarpoolService>().listPostings(type) }
                }
            },
            render = { panel, postings ->
                postings.forEach { posting ->
                    renderCarpoolCard(
                        panel,
                        posting,
                        isMember = isMember,
                        ui = ui,
                        openForm = { formController.open(it) },
                        onSaved = { refreshAll() },
                    )
                }
            },
        )

    refreshAll = {
        myRidesSection.reload()
        feedSection.reload()
    }
    if (isMember) {
        formController =
            collapsibleCreateForm<CarpoolFormPrefill>(
                actionSlot = header.actionSlot,
                formHost = formHost,
                buttonLabel = tr("Eintrag erstellen"),
                formId = "carpool-create-form",
            ) { prefill, close -> buildCarpoolForm(this, prefill, close) { refreshAll() } }
        typeFilter?.subscribe { feedSection.reload() }
        // KVision's CheckInput.subscribe calls the observer once at registration (see BankStatementImportScreen): skip that synthetic event.
        var isInitialOnlyMineEvent = true
        onlyMineCheck?.subscribe {
            if (isInitialOnlyMineEvent) {
                isInitialOnlyMineEvent = false
                return@subscribe
            }
            feedSection.reload()
        }
    }

    refreshAll()
}

/** Filter term of "Nur meine" without a type filter: never shown, [dataSection]'s `noMatchText` turns it into the empty text of the own entries. */
private const val CARPOOL_ONLY_MINE_TERM = "carpool-only-mine"

/** [tr]/[gettext] label for the currently selected feed type filter -- used as [DataSection]'s `filterTerm`/`noMatchText`. */
private fun carpoolTypeFilterLabel(rawTypeValue: String): String =
    when (CarpoolPostingType.valueOf(rawTypeValue)) {
        CarpoolPostingType.OFFER -> tr("Angebote")
        CarpoolPostingType.REQUEST -> tr("Gesuche")
    }

/** What "Bearbeiten" / "Duplizieren" hand to the shared create form: the posting to edit, or the one to copy (never both). */
internal class CarpoolFormPrefill(
    val editing: CarpoolPostingDto?,
    val duplicateFrom: CarpoolPostingDto?,
)

/**
 * One card. [openForm] opens the shared collapsible form (one host under the page header, so never two forms at once); [onSaved] reloads
 * both lists. Passed in as functions: no global registry between the screen and its cards.
 */
private fun renderCarpoolCard(
    panel: SimplePanel,
    posting: CarpoolPostingDto,
    isMember: Boolean,
    ui: CarpoolUi,
    openForm: (CarpoolFormPrefill) -> Unit,
    onSaved: () -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center flex-wrap") }
    headerRow.typeBadge(
        if (posting.type == CarpoolPostingType.OFFER) tr("Angebot") else tr("Gesuch"),
        if (posting.type == CarpoolPostingType.OFFER) "success" else "primary",
    )
    // V1.9.186: the card SHOWS a tidied place ("06217 MERSEBURG (SAALE), DEUTSCHLAND" -> "Merseburg (Saale)"); the stored text is untouched
    // and the full original stays one hover away in the title. `title` is an attribute: resolved text only, never a tr() marker.
    val from = formatCarpoolPlace(posting.fromPlace)
    val to = formatCarpoolPlace(posting.toPlace)
    val title = headerRow.untrustedCardTitle(gettext("%1 → %2", from, to))
    title.setAttribute("title", resolvedAttributeText(sanitizeUntrustedI18nText("${posting.fromPlace} → ${posting.toPlace}")))
    if (posting.isOwn) headerRow.typeBadge(tr("Eigener Eintrag"), "secondary")
    if (posting.isPast) headerRow.statusBadge(tr("Abgelaufen"), "secondary")
    posting.myParticipation?.let { mine ->
        headerRow.carpoolStatusBadge(carpoolBadgeSpec(mine.status, mine.initiator, viewerIsDriver = false))
    }
    // The three own-entry actions sit in the title row as icon buttons; the name carries the route so that a screen reader can tell the
    // cards apart. The text is data-bearing, so it is sanitized as a whole before it becomes `title`/`aria-label`.
    val ownActions = if (posting.isOwn) headerRow.hPanel(spacing = 4) { addCssClasses("ms-auto flex-wrap") } else null
    // The delete confirmation gets a full-width row of its own right under the title row (hidden until "Löschen" is pressed).
    val confirmBox = if (posting.isOwn) row.hPanel(spacing = 8) { addCssClasses("w-100 align-items-center flex-wrap") } else null
    confirmBox?.hide()

    row.div(formatDate(posting.departureDate) + carpoolDepartureTimeSuffix(posting.departureTime)) { addCssClasses("text-muted small") }

    carpoolSeatsText(posting)?.let { text -> row.div(text) }
    posting.notes?.takeIf { it.isNotBlank() }?.let { notes -> row.untrustedDiv(notes) { addCssClasses("small") } }
    row.untrustedDiv(gettext("von %1", posting.authorDisplayName)) { addCssClasses("text-muted small") }
    posting.meetingNote?.takeIf { it.isNotBlank() }?.let { renderMeetingNoteLine(row, it) }

    if (ownActions != null) {
        ownActions
            .actionButton(
                ActionIcon.EDIT,
                sanitizeUntrustedI18nText(gettext("Fahrt %1 nach %2 bearbeiten", from, to)),
                style = ButtonStyle.OUTLINESECONDARY,
                iconOnly = true,
            ).onClick { openForm(CarpoolFormPrefill(editing = posting, duplicateFrom = null)) }
        ownActions
            .actionButton(
                ActionIcon.COPY,
                sanitizeUntrustedI18nText(gettext("Fahrt %1 nach %2 duplizieren", from, to)),
                style = ButtonStyle.OUTLINESECONDARY,
                iconOnly = true,
            ).onClick { openForm(CarpoolFormPrefill(editing = null, duplicateFrom = posting)) }
        renderCarpoolDeleteControl(
            actionHost = ownActions,
            confirmBox = checkNotNull(confirmBox),
            posting = posting,
            deleteLabel = sanitizeUntrustedI18nText(gettext("Fahrt %1 nach %2 löschen", from, to)),
            onSaved = onSaved,
        )
        if (posting.type == CarpoolPostingType.OFFER) {
            renderOwnerPanel(row = row, posting = posting, ui = ui, openRequests = if (posting.isPast) 0 else posting.openRequests)
        }
        return
    }
    if (posting.isPast) return
    val actionRow = row.hPanel(spacing = 8) { addCssClass("mt-1") }
    if (carpoolMayRequestRide(posting)) {
        val requestButton = actionRow.actionButton(ActionIcon.REQUEST_RIDE, tr("Mitfahrt anfragen"), style = ButtonStyle.PRIMARY)
        requestButton.onClick {
            runGuardedAction(requestButton) {
                if (guarded { rpcService<ICarpoolService>().requestParticipation(posting.id) } != null) {
                    val message = gettext("Mitfahrt angefragt.")
                    notifySuccess(message)
                    ui.announce(message)
                    ui.onChanged()
                }
            }
        }
    }
    // A direct message needs an ACTIVE recipient AND sender: guests and friends have no such channel (they get the meeting note instead).
    if (isMember) {
        var contactPanel: SimplePanel? = null
        actionRow.actionButton(ActionIcon.SEND, tr("Kontakt aufnehmen"), style = ButtonStyle.OUTLINESECONDARY).onClick {
            if (contactPanel != null) return@onClick
            contactPanel = renderCarpoolContactForm(row = row, posting = posting) { contactPanel = null }
        }
    }
}

/** InlineConfirmGate-Riegel gegen einen doppelten Löschvorgang (Forstall-Ruling: Bestätigung inline, kein zweites Modal). */
private fun renderCarpoolDeleteControl(
    actionHost: SimplePanel,
    confirmBox: SimplePanel,
    posting: CarpoolPostingDto,
    deleteLabel: String,
    onSaved: () -> Unit,
) {
    val gate = InlineConfirmGate()
    val deleteButton = actionHost.actionButton(ActionIcon.DELETE, deleteLabel, style = ButtonStyle.OUTLINEDANGER, iconOnly = true)

    fun syncButton() {
        deleteButton.disabled = gate.blocked
    }

    deleteButton.onClick {
        if (!gate.openConfirmation()) return@onClick
        syncButton()
        confirmBox.removeAll()
        confirmBox.div(tr("Wirklich löschen?")) { addCssClasses("align-self-center") }
        confirmBox.actionButton(ActionIcon.BACK, tr("Zurück"), style = ButtonStyle.SECONDARY).onClick {
            if (!gate.cancelConfirmation()) return@onClick
            confirmBox.hide()
            syncButton()
        }
        val finalButton = confirmBox.actionButton(ActionIcon.DELETE, tr("Endgültig löschen"), style = ButtonStyle.DANGER)
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
    val sendButton = newActionButton(ActionIcon.SEND, tr("Senden"), ButtonStyle.PRIMARY)
    val cancelButton = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY)
    form.buttons(primary = sendButton, cancel = cancelButton)
    cancelButton.onClick {
        row.remove(panel)
        onClosed()
    }
    sendButton.onClick {
        form.submit(sendButton) {
            val result = guarded { rpcService<ICarpoolService>().contactAuthor(posting.id, messageField.value.trim()) }
            if (result != null) {
                notifySuccess(tr("Nachricht wurde gesendet. Antworten finden Sie unter Nachrichten."))
                row.remove(panel)
                onClosed()
            }
        }
    }
    return panel
}

/**
 * The create/edit/duplicate form, built into the shared host of [collapsibleCreateForm]. [prefill] is `null` for a new entry. The
 * fingerprint ([snapshot]) covers all seven inputs, so a duplicate (pre-filled) counts as unchanged until the person types.
 */
private fun buildCarpoolForm(
    host: SimplePanel,
    prefill: CarpoolFormPrefill?,
    close: (saved: Boolean) -> Unit,
    onSaved: () -> Unit,
): FormSnapshot {
    val editing = prefill?.editing
    val duplicateFrom = prefill?.duplicateFrom
    val base = duplicateFrom ?: editing
    val panel = host.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
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
        form.localDateField(
            label = tr("Abfahrtsdatum"),
            value = if (duplicateFrom != null) null else base?.departureDate,
            required = true,
            requiredMessage = tr("Bitte ein gültiges Datum angeben."),
        )
    val timeField =
        form.localTimeField(
            label = tr("Abfahrtszeit (optional)"),
            value = if (duplicateFrom != null) null else base?.departureTime,
            stepMinutes = 5,
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

    val saveButton = newActionButton(ActionIcon.SAVE, tr("Speichern"), ButtonStyle.PRIMARY)
    val cancelButton = collapseCancelButton(close)
    form.buttons(primary = saveButton, cancel = cancelButton)

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
                    departureDate = checkNotNull(dateField.localDateOrNull()),
                    departureTime = timeField.localTimeOrNull(),
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
                close(true)
                onSaved()
            }
        }
    }
    return form.snapshot()
}

/**
 * The ", ab HH:MM Uhr" / ", Uhrzeit flexibel" tail of a card's date line. Must return resolved text via
 * `gettext`, never a `tr()` marker token: the result is concatenated behind the formatted date, and KVision
 * only resolves a marker at the very start of a widget's content -- mid-string it leaked as `###KvI18nS###`.
 */
internal fun carpoolDepartureTimeSuffix(departureTime: LocalTime?): String =
    departureTime?.let { gettext(", ab %1 Uhr", formatLocalTime(it, currentTemporalLocale())) } ?: gettext(", Uhrzeit flexibel")
