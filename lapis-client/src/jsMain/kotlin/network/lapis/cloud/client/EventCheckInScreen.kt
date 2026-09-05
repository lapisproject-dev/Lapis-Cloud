package network.lapis.cloud.client

import io.kvision.form.text.text
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.EventCheckInOutcome
import network.lapis.cloud.shared.domain.EventCheckInResultDto
import network.lapis.cloud.shared.domain.EventCheckInRosterDto
import network.lapis.cloud.shared.domain.EventCheckInRowDto
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventTicketCode
import network.lapis.cloud.shared.rpc.IEventService
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent

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
 */
fun renderEventCheckInScreen(
    container: SimplePanel,
    eventId: String,
) {
    val root =
        container.vPanel(spacing = 12) {
            addCssClass("mx-auto")
            width = 800.px
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

    val codeRow = root.hPanel(spacing = 8) { addCssClasses("align-items-end") }
    val codeField = codeRow.text(label = tr("Ticket-Code"))
    val codeSubmitButton = codeRow.button(tr("Prüfen"), style = ButtonStyle.PRIMARY)

    val searchField = root.text(label = tr("Name suchen"))
    val listPanel = root.vPanel(spacing = 4)

    var roster: EventCheckInRosterDto? = null

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
                        onResult = { newResult -> renderResult(newResult) },
                        // Fix (review MEDIUM): the list check-in path used to only show the result
                        // banner and never re-fetch the roster (unlike submitCode()'s own code
                        // path, which always calls refreshRoster() after a successful check-in) --
                        // the just-checked-in row kept its stale "Bestätigt" badge/enabled button
                        // and the "%1 von %2" counter never moved until the next code scan or a
                        // manual reload. Also used by the reissue-ticket button (a second review
                        // MEDIUM fix) so a successful reissue's `hasTicket`/status change is
                        // likewise visible without a manual reload.
                        onRefresh = { refreshRoster() },
                    )
                }
            }
        }
    }

    fun submitCode() {
        val raw = codeField.value.orEmpty()
        // Local pre-validation with the SAME grammar the server applies -- rejects obvious garbage
        // without a round-trip (EventTicketCode is shared commonMain, see its own KDoc).
        if (EventTicketCode.extractAndCanonicalize(raw) == null) {
            renderResult(EventCheckInResultDto(outcome = EventCheckInOutcome.UNKNOWN_CODE))
            return
        }
        AppScope.launch {
            val result = guarded { rpcService<IEventService>().checkInByCode(eventId = eventId, code = raw) } ?: return@launch
            codeField.value = ""
            renderResult(result)
            refreshRoster()
        }
    }
    codeSubmitButton.onClick { submitCode() }
    codeRow.addAfterInsertHook { vnode ->
        val rowElement = vnode.elm as? HTMLElement
        val inputElement = rowElement?.querySelector("input") as? HTMLInputElement
        inputElement?.setAttribute("placeholder", "ABCD-EFGH-JKMN-PQRS")
        inputElement?.setAttribute("autocapitalize", "characters")
        inputElement?.setAttribute("autocomplete", "off")
        inputElement?.setAttribute("spellcheck", "false")
        inputElement?.focus()
        inputElement?.addEventListener("keydown", { event ->
            val keyEvent = event as? KeyboardEvent
            if (keyEvent?.key == "Enter") {
                keyEvent.preventDefault()
                submitCode()
            }
        })
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
            detailsPanel.div("${tr("E-Mail")}: ${row.email ?: "-"}")
            detailsPanel.div("${tr("Status")}: ${row.status}")
            detailsPanel.div("${tr("Ticket ausgestellt")}: ${if (row.hasTicket) tr("Ja") else tr("Nein")}")
            if (row.checkedInAt != null) {
                detailsPanel.div("${tr("Eingecheckt um")}: ${row.checkedInAt} (${row.checkedInByDisplayName ?: "-"})")
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
