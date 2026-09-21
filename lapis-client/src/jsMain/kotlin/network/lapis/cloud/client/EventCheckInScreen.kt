package network.lapis.cloud.client

import io.kvision.core.Widget
import io.kvision.core.onEvent
import io.kvision.form.text.Text
import io.kvision.form.text.text
import io.kvision.html.Autocomplete
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.HPanel
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventCheckInOutcome
import network.lapis.cloud.shared.domain.EventCheckInResultDto
import network.lapis.cloud.shared.domain.EventCheckInRosterDto
import network.lapis.cloud.shared.domain.EventCheckInRowDto
import network.lapis.cloud.shared.domain.EventInvoiceRequestDto
import network.lapis.cloud.shared.domain.EventRegistrationDto
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventTicketCode
import network.lapis.cloud.shared.rpc.IEventService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/**
 * Welle V1.4.3.2 "Veranstaltungen: Ticketing/QR-Codes" -- the door. Design decisions (see the wave
 * plan §7.2):
 *
 * 1. **The guest list is the primary object**, not the code field -- it loads immediately and shows
 *    every registration with its status, searchable by name. The code field is a fast path for a
 *    guest with a phone/printed ticket, not the only way in.
 * 2. **"Bereits eingecheckt" is rendered NEUTRALLY, never as an error** -- a second scan of the same
 *    ticket is an everyday, unremarkable event at a real door (a guest steps back in from smoking),
 *    not a fault.
 * 3. **Row expansion, not a separate screen**, is where e-mail/ticket-status/"Ticket neu ausstellen"
 *    live -- a dedicated BOARD registration-list screen does not exist yet (V1.4.3.1 built only the
 *    RPC, `IEventService.listRegistrations`), so Neuausgabe lives here as a documented, deliberate
 *    scope reduction rather than blocking this wave on building that screen too.
 *
 * **Welle V1.4.3.6 "Externe Rechnungsstellung" addendum.** Review MAJOR fix: `issueEventInvoice`
 * shipped with no client-UI caller at all -- a TREASURER could only reach it via a raw RPC/HTTP
 * call. Same pragmatic "extend the existing check-in surface" precedent as the V1.4.3.4 room-
 * assignment addendum on [renderEventCheckInSelectionScreen] (no dedicated registration-admin
 * screen exists to put this on instead). Each row gets an inline invoice line
 * ([renderInvoiceLine]), visible only for [AccountRole.TREASURER]/[AccountRole.ADMIN] -- the same
 * tier `IEventService.issueEventInvoice` itself requires server-side -- so a plain BOARD caller
 * (who CAN reach this whole screen, see `Routing.kt`'s `requireRole(BOARD, ADMIN)` on
 * `EVENT_CHECKIN_EVENT`) sees nothing new. The per-registration `feeAmount`/`openItemId`/
 * `invoiceIssuedAt` this needs are NOT part of [EventCheckInRowDto] (that roster DTO deliberately
 * carries only door-scanning fields) -- fetched via a SEPARATE, additional
 * `IEventService.listRegistrations(eventId)` call, role-safe here because that RPC's own
 * `EVENT_MANAGE_ROLES` (BOARD/ADMIN) gate is already satisfied by anyone who got past this
 * screen's route guard.
 */
fun renderEventCheckInScreen(
    container: SimplePanel,
    eventId: String,
) {
    val root =
        container.vPanel(spacing = 12) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 800.px
            marginTop = 24.px
        }

    val offlineBanner =
        root.div(tr("Keine Verbindung -- bitte auf Papier abhaken und später nachtragen.")) {
            addCssClasses("alert alert-warning")
            hide()
        }
    val headerTitle = root.h1("")
    val headerMeta = root.div("") { addCssClasses("text-muted") }
    val counterLine = root.div("") { addCssClasses("fw-bold") }

    val resultBanner = root.div("") { hide() }

    // Welle V1.4.29 (W4b): Das Ticket-Feld ist ein Feld der Formular-Grammatik -- und der Barcode-Scanner an der Tür.
    // Ein Scanner tippt den Code und sendet `Enter`: das bleibt der schnelle Weg (unten, `keydown`), auch wenn das Feld gerade
    // einen Fehler zeigt. Der Startfokus kommt über [addWithLifecycle] (Hook VOR dem Einhängen), nicht über einen späten
    // `addAfterInsertHook` (ClientLateHookRatchetTest).
    val codeForm = root.lapisForm()
    val codeRow = HPanel(spacing = 8) { addCssClasses("align-items-end") }
    val codeField =
        codeForm.textField(
            label = tr("Ticket-Code"),
            required = true,
            autocomplete = Autocomplete.OFF,
            host = codeRow,
            rule = { ticketCodeCheck(it) },
            init = { control ->
                control.placeholder = "ABCD-EFGH-JKMN-PQRS"
                // Am <input> selbst (nicht am Wrapper des Controls): nur dort wirken Autokorrektur- und Großschreibungs-Bitten.
                (control.input as? Widget)?.let { input ->
                    input.setAttribute("autocapitalize", "characters")
                    input.setAttribute("spellcheck", "false")
                }
            },
        )
    val codeSubmitButton = codeRow.button(tr("Prüfen"), style = ButtonStyle.PRIMARY)
    // The row is complete (field and button inside) BEFORE it is added, so the insert hook finds the input.
    codeForm.panel.addWithLifecycle(
        widget = codeRow,
        onInsert = { vnode -> ((vnode.elm as? HTMLElement)?.querySelector("input") as? HTMLInputElement)?.focus() },
    )
    codeForm.finish()

    val searchField = root.text(label = tr("Name suchen"))
    val listPanel = root.vPanel(spacing = 4)

    var roster: EventCheckInRosterDto? = null
    // See class KDoc "Welle V1.4.3.6 addendum" -- gates the invoice line, and whether the extra
    // `listRegistrations` round-trip below even runs at all (a plain BOARD caller never needs it).
    val canInvoice = AppState.hasRole(AccountRole.TREASURER, AccountRole.ADMIN)
    var registrationsById: Map<String, EventRegistrationDto> = emptyMap()

    fun renderResult(result: EventCheckInResultDto) {
        val (cssClass, message) =
            when (result.outcome) {
                EventCheckInOutcome.OK -> "alert alert-success" to gettext("Eingecheckt: %1", result.participantName ?: "")
                EventCheckInOutcome.ALREADY_CHECKED_IN -> {
                    val at = result.checkedInAt?.toString() ?: ""
                    val by = result.checkedInByDisplayName ?: ""
                    "alert alert-secondary" to gettext("Bereits eingecheckt um %1 durch %2.", at, by)
                }
                EventCheckInOutcome.WRONG_EVENT -> "alert alert-warning" to tr("Dieses Ticket gehört zu einer anderen Veranstaltung.")
                EventCheckInOutcome.NOT_CONFIRMED ->
                    "alert alert-warning" to tr("Anmeldung noch nicht bestätigt (Zahlung offen oder Warteliste).")
                EventCheckInOutcome.CANCELLED_REGISTRATION -> "alert alert-danger" to tr("Diese Anmeldung wurde storniert.")
                EventCheckInOutcome.UNKNOWN_CODE -> "alert alert-danger" to tr("Code unbekannt.")
            }
        resultBanner.removeCssClass("alert-success")
        resultBanner.removeCssClass("alert-secondary")
        resultBanner.removeCssClass("alert-warning")
        resultBanner.removeCssClass("alert-danger")
        resultBanner.addCssClasses(cssClass)
        resultBanner.content = message
        resultBanner.show()
    }

    // `renderRoster` and `refreshRoster` call each other (a row's check-in/reissue button
    // triggers a refresh, which re-renders the roster) -- genuine mutual recursion between two
    // plain local `fun`s does not compile in Kotlin (whichever is declared second would need a
    // forward reference to the other), so `renderRoster` is a `lateinit var` of function type,
    // declared here and assigned its body below AFTER `refreshRoster` exists to close over.
    lateinit var renderRoster: (String) -> Unit

    fun refreshRoster() {
        AppScope.launch {
            val fresh = guarded { rpcService<IEventService>().openCheckIn(eventId) } ?: return@launch
            roster = fresh
            if (canInvoice) {
                // Not `?: return@launch` -- a failed fetch here (e.g. a transient error) should not
                // blank the roster that just loaded successfully above; it just means no invoice
                // line renders this refresh (`registrationsById` simply stays at its previous value).
                registrationsById =
                    guarded { rpcService<IEventService>().listRegistrations(eventId) }
                        ?.associateBy { it.id }
                        ?: registrationsById
            }
            headerTitle.content = fresh.eventTitle
            headerMeta.content = listOfNotNull("${fresh.startsAt}", fresh.locationText).joinToString(" · ")
            renderRoster(searchField.value.orEmpty())
        }
    }

    renderRoster = { filter ->
        listPanel.removeAll()
        val current = roster
        if (current != null) {
            val filtered =
                if (filter.isBlank()) {
                    current.rows
                } else {
                    current.rows.filter { it.displayName.contains(filter, ignoreCase = true) }
                }
            counterLine.content = gettext("%1 von %2 eingecheckt", current.checkedInCount.toString(), current.confirmedCount.toString())
            if (filtered.isEmpty()) {
                listPanel.p(tr("Keine Treffer."))
            } else {
                filtered.forEach { row ->
                    renderRosterRow(
                        listPanel = listPanel,
                        eventId = eventId,
                        row = row,
                        registration = registrationsById[row.registrationId],
                        canInvoice = canInvoice,
                        onResult = { newResult -> renderResult(newResult) },
                        // Fix (review MEDIUM): the list check-in path used to only show the result
                        // banner and never re-fetch the roster (unlike submitCode()'s own code
                        // path, which always calls refreshRoster() after a successful check-in) --
                        // the just-checked-in row kept its stale "Bestätigt" badge/enabled button
                        // and the "%1 von %2" counter never moved until the next code scan or a
                        // manual reload. Also used by the reissue-ticket button (a second review
                        // MEDIUM fix) so a successful reissue's `hasTicket`/status change is
                        // likewise visible without a manual reload. Also used by the invoice
                        // action/modal (Welle V1.4.3.6) for the same reason.
                        onRefresh = { refreshRoster() },
                    )
                }
            }
        }
    }

    /** Räumt den Ergebnis-Banner: er ist das Bediensignal der Tür und darf nie das Ergebnis des VORIGEN Scans zeigen. */
    fun clearResult() {
        resultBanner.hide()
        resultBanner.content = ""
        resultBanner.removeCssClass("alert-success")
        resultBanner.removeCssClass("alert-secondary")
        resultBanner.removeCssClass("alert-warning")
        resultBanner.removeCssClass("alert-danger")
    }

    fun submitCode() {
        // Ein zweiter Scan, während der erste noch unterwegs ist, wird nicht doppelt gesendet.
        if (codeSubmitButton.disabled) return
        // Der Banner wird VOR jedem Absenden geräumt: nach einem gültigen Scan ("Eingecheckt: Anna") darf ein Unsinn-Code oder ein
        // Netzfehler den grünen Banner nicht stehen lassen -- die Tür würde den nächsten Gast durchwinken.
        clearResult()
        // Die lokale Vorprüfung (dieselbe Grammatik wie auf dem Server, `EventTicketCode` ist shared commonMain) ist die Feldregel:
        // offensichtlicher Unsinn wird am Feld gemeldet, ohne Round-Trip. ZUSÄTZLICH zeigt der Banner wie früher das rote
        // "Code unbekannt." -- der Banner ist das Signal, auf das die Tür schaut, nicht das Feld.
        if (!codeForm.validateAndReport()) {
            renderResult(EventCheckInResultDto(outcome = EventCheckInOutcome.UNKNOWN_CODE))
            return
        }
        codeForm.runBusy(codeSubmitButton) {
            val raw = codeField.value
            val result = guarded { rpcService<IEventService>().checkInByCode(eventId = eventId, code = raw) } ?: return@runBusy
            // `reset()` statt `value = ""`: räumt auch Fehler und Berührt-Zustand; der Fokus zurück ins Feld, sonst tippt der
            // nächste Scan ins Leere.
            codeField.reset()
            codeField.focus()
            renderResult(result)
            refreshRoster()
        }
    }
    codeSubmitButton.onClick { submitCode() }
    (codeField.control as Text).onEvent {
        keydown = { event ->
            if (event.key == "Enter") {
                event.preventDefault()
                submitCode()
            }
        }
    }

    searchField.subscribe { value -> renderRoster(value.orEmpty()) }

    // A stripped-down connectivity hint, not a full offline check-in mode (deliberately out of
    // scope, see wave plan "Ausdrücklich nicht") -- `navigator.onLine` is a best-effort signal, not
    // a guarantee, which is exactly the honesty level this banner needs.
    fun updateOfflineBanner() {
        if (window.navigator.onLine) offlineBanner.hide() else offlineBanner.show()
    }
    updateOfflineBanner()
    window.addEventListener("online", { updateOfflineBanner() })
    window.addEventListener("offline", { updateOfflineBanner() })

    refreshRoster()
}

private fun renderRosterRow(
    listPanel: SimplePanel,
    eventId: String,
    row: EventCheckInRowDto,
    registration: EventRegistrationDto?,
    canInvoice: Boolean,
    onResult: (EventCheckInResultDto) -> Unit,
    onRefresh: () -> Unit,
) {
    val rowPanel =
        listPanel.vPanel(spacing = 0) {
            addCssClasses("border rounded p-2")
        }
    val headerLine =
        rowPanel.hPanel(spacing = 12) {
            addCssClasses("align-items-center justify-content-between")
        }
    val nameArea = headerLine.hPanel(spacing = 8) { addCssClasses("align-items-center flex-grow-1") }
    nameArea.span(row.displayName) { addCssClasses("fw-bold") }
    val (badgeColor, badgeText) = statusBadgeSpec(row)
    nameArea.statusBadge(badgeText, badgeColor)
    // A dedicated toggle BUTTON, not a click handler on the row itself -- `HPanel`/`Div` do not
    // carry KVision's `onClick` DSL (only interactive widgets like `Button`/`Link` do), and a
    // separate button also sidesteps any bubbling concern with the check-in button below (a true
    // sibling, never a descendant).
    val detailsToggleButton = headerLine.button(tr("Details"), style = ButtonStyle.OUTLINESECONDARY) { addCssClass("ms-2") }
    val checkInButton =
        headerLine.button(tr("Einchecken"), style = ButtonStyle.SUCCESS) {
            addCssClass("ms-2")
            disabled = row.checkedInAt != null || row.status != EventRegistrationStatus.CONFIRMED
        }
    checkInButton.onClick {
        AppScope.launch {
            val result =
                guarded { rpcService<IEventService>().checkInRegistration(eventId = eventId, registrationId = row.registrationId) }
                    ?: return@launch
            onResult(result)
            // Fix (review MEDIUM): this used to stop at onResult(result) -- the result banner
            // showed, but this row's own badge/button state and the roster's "%1 von %2" counter
            // came from the now-stale DTO closed over at render time, so they never reflected the
            // check-in that just happened until the next code scan or a manual reload.
            onRefresh()
        }
    }

    renderInvoiceLine(rowPanel = rowPanel, row = row, registration = registration, canInvoice = canInvoice, onRefresh = onRefresh)

    val detailsPanel =
        rowPanel.vPanel(spacing = 4) {
            addCssClasses("mt-2 ps-2 border-top pt-2")
            hide()
        }
    var expanded = false
    detailsToggleButton.onClick {
        expanded = !expanded
        if (expanded) {
            detailsPanel.removeAll()
            detailsPanel.div("${gettext("E-Mail")}: ${row.email ?: "-"}")
            detailsPanel.div("${gettext("Status")}: ${row.status}")
            detailsPanel.div("${gettext("Ticket ausgestellt")}: ${if (row.hasTicket) gettext("Ja") else gettext("Nein")}")
            if (row.checkedInAt != null) {
                detailsPanel.div("${gettext("Eingecheckt um")}: ${row.checkedInAt} (${row.checkedInByDisplayName ?: "-"})")
            }
            val reissueButton = detailsPanel.button(tr("Ticket neu ausstellen"), style = ButtonStyle.OUTLINEDANGER)
            reissueButton.disabled = row.status != EventRegistrationStatus.CONFIRMED
            reissueButton.onClick {
                confirmDialog(
                    title = tr("Ticket neu ausstellen"),
                    message = tr("Das bisher ausgestellte Ticket wird dadurch ungültig."),
                    confirmLabel = tr("Neu ausstellen"),
                ) {
                    AppScope.launch {
                        // Fix (review MEDIUM): `guarded`'s result was previously discarded, so the
                        // success toast fired UNCONDITIONALLY -- even when `guarded` had already
                        // shown its own error toast (e.g. ConflictException because the registration
                        // was cancelled/expired in the meantime) and returned null without ever
                        // calling reissueTicket's mail-sending path to completion. refreshRoster()
                        // also only makes sense on the success path, since it is what would surface
                        // this row's `hasTicket`/status truly changing.
                        val outcome = guarded { rpcService<IEventService>().reissueTicket(row.registrationId) }
                        if (outcome != null) {
                            notifySuccess(tr("Neues Ticket ausgestellt und versendet."))
                            onRefresh()
                        }
                    }
                }
            }
            detailsPanel.show()
        } else {
            detailsPanel.hide()
        }
    }
}

/**
 * Welle V1.4.3.6 "Externe Rechnungsstellung" -- see [renderEventCheckInScreen] class KDoc
 * addendum. Renders nothing at all unless [canInvoice] AND [registration] is non-null AND the
 * registration's frozen `feeAmount > 0` -- a free event, or a caller without the RPC-level
 * `EVENT_INVOICE_ROLES` tier, gets no line, not a disabled one (D-UNAVAILABLE precedent, see
 * [renderFinTsCell] in `BankAccountsScreen.kt`). Once invoiced ([EventRegistrationDto.openItemId]
 * non-null), the action button is replaced by a status line + the existing PDF download route --
 * no new download plumbing, [MailmergeHttp.eventInvoiceUrl] just points at the route
 * `MailmergeRoutes.kt`'s `registerMailmergeRoutes` already registers for this wave.
 */
private fun renderInvoiceLine(
    rowPanel: SimplePanel,
    row: EventCheckInRowDto,
    registration: EventRegistrationDto?,
    canInvoice: Boolean,
    onRefresh: () -> Unit,
) {
    if (!canInvoice || registration == null) return
    if (registration.feeAmount.toDouble() <= 0.0) return
    val invoiceLine = rowPanel.hPanel(spacing = 8) { addCssClasses("align-items-center mt-1") }
    if (registration.openItemId != null) {
        invoiceLine.div(
            gettext(
                "Rechnung gestellt am %1 durch %2 (Offener Posten).",
                registration.invoiceIssuedAt?.toString().orEmpty(),
                registration.invoiceIssuedByDisplayName.orEmpty(),
            ),
        ) { addCssClasses("text-muted small") }
        invoiceLine.link(tr("Rechnung (PDF)"), url = MailmergeHttp.eventInvoiceUrl(row.registrationId), target = "_blank")
    } else {
        val invoiceButton = invoiceLine.button(tr("Rechnung stellen"), style = ButtonStyle.OUTLINEPRIMARY)
        invoiceButton.onClick {
            eventInvoiceModal(row = row, onIssued = onRefresh)
        }
    }
}

/**
 * The form modal behind [renderInvoiceLine]'s "Rechnung stellen" button -- same
 * `Modal`+`modal.addButton`+`errorBox` idiom as `bankAccountEditModal` in `BankAccountsScreen.kt`.
 * Every billing-address field is optional (mirrors [EventInvoiceRequestDto] itself); only
 * `dueInDays` is validated client-side (server re-validates regardless, see
 * `IEventService.issueEventInvoice` KDoc) since a non-positive value would otherwise round-trip
 * into a `BadRequestException` toast with no field-level indication of what was wrong.
 */
internal fun eventInvoiceModal(
    row: EventCheckInRowDto,
    onIssued: () -> Unit,
) {
    val modal = Modal(caption = gettext("Rechnung stellen: %1", row.displayName))
    // Formular-Grammatik (V1.4.29): die Rechnungsanschrift ist optional (spiegelt [EventInvoiceRequestDto]), nur die
    // Fälligkeitsfrist ist Pflicht => Fall (a), Stern nur an der Frist. Kein "(optional)" im Label.
    val form = modal.lapisForm()
    val streetField = form.textField(label = tr("Straße"))
    val postalCodeField = form.textField(label = tr("PLZ"))
    val cityField = form.textField(label = tr("Ort"))
    val countryField = form.textField(label = tr("Land"))
    val dueInDaysField =
        form.textField(
            label = tr("Fälligkeitsfrist (Tage)"),
            value = "14",
            required = true,
            rule = { value ->
                if ((value.trim().toIntOrNull() ?: 0) > 0) {
                    FieldCheck.Ok
                } else {
                    FieldCheck.Invalid(gettext("Fälligkeitsfrist muss eine positive Zahl von Tagen sein."))
                }
            },
        )
    form.finish()

    val submitButton = Button(tr("Rechnung stellen"), style = ButtonStyle.PRIMARY)
    submitButton.onClick {
        form.submit(submitButton) {
            val input =
                EventInvoiceRequestDto(
                    registrationId = row.registrationId,
                    billingStreet = streetField.value.trim().takeIf { it.isNotBlank() },
                    billingPostalCode = postalCodeField.value.trim().takeIf { it.isNotBlank() },
                    billingCity = cityField.value.trim().takeIf { it.isNotBlank() },
                    billingCountry = countryField.value.trim().takeIf { it.isNotBlank() },
                    dueInDays = dueInDaysField.value.trim().toInt(),
                )
            guarded { rpcService<IEventService>().issueEventInvoice(input) } ?: return@submit
            modal.hide()
            notifySuccess(tr("Rechnung wurde gestellt."))
            onIssued()
        }
    }
    modal.addButton(Button(tr("Abbrechen"), style = ButtonStyle.SECONDARY).apply { onClick { modal.hide() } })
    modal.addButton(submitButton)
    modal.show()
}

/** Feldregel des Ticket-Felds: dieselbe Grammatik wie der Server (`EventTicketCode`), damit offensichtlicher Unsinn keinen Round-Trip braucht. */
internal fun ticketCodeCheck(raw: String): FieldCheck =
    if (EventTicketCode.extractAndCanonicalize(raw) == null) FieldCheck.Invalid(gettext("Code unbekannt.")) else FieldCheck.Ok

/** Outcome/status -> (Bootstrap-Farbe, deutsches Label) -- [statusBadge]'s own WCAG-1.4.1-Farbe-ist-nie-das-einzige-Signal-Regel gilt auch hier. */
private fun statusBadgeSpec(row: EventCheckInRowDto): Pair<String, String> =
    when {
        row.checkedInAt != null -> "secondary" to tr("Eingecheckt")
        row.status == EventRegistrationStatus.CONFIRMED -> "success" to tr("Bestätigt")
        row.status == EventRegistrationStatus.PENDING_PAYMENT -> "warning" to tr("Zahlung offen")
        row.status == EventRegistrationStatus.WAITLISTED -> "info" to tr("Warteliste")
        row.status == EventRegistrationStatus.CANCELLED -> "danger" to tr("Storniert")
        row.status == EventRegistrationStatus.EXPIRED -> "danger" to tr("Abgelaufen")
        else -> "secondary" to row.status.toString()
    }
