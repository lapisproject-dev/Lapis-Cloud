package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.EventQuery
import network.lapis.cloud.shared.domain.EventVolunteerShiftDto
import network.lapis.cloud.shared.domain.EventVolunteerShiftStatus
import network.lapis.cloud.shared.domain.EventVolunteerSignupStatus
import network.lapis.cloud.shared.rpc.IEventService
import network.lapis.cloud.shared.rpc.IEventVolunteerService

/**
 * Welle V1.4.3.7 "Helfer-/Schichtplanung für Veranstaltungen" -- Selbstbedienungs-Screen: jedes
 * authentifizierte Mitglied kann sich für eine Schicht an-/abmelden. Es gab VOR dieser Welle KEINEN
 * bestehenden Screen, der `IEventService.registerSelf`-artige Selbstbedienung für Helferschichten
 * anbot -- dies ist ein neuer, bewusst minimaler Screen (Event-Auswahl + Schicht-Liste +
 * Anmelden/Zusage-zurückziehen-Button je Zeile), keine Erweiterung eines bestehenden. Route
 * `/my-volunteer-shifts`, `requireAuth` (KEIN `requireRole`) in `Routing.kt` -- jedes
 * authentifizierte Mitglied darf sich selbst eintragen, exakt wie bei
 * `IEventService.registerSelf`/`.cancelOwnRegistration`.
 *
 * Die BOARD/ADMIN-Schicht-VERWALTUNG (anlegen/bearbeiten/stornieren/Roster) lebt separat in
 * `EventVolunteerShiftsScreen.kt`, nicht hier.
 */
fun renderMyVolunteerShiftsScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 800.px
            marginTop = 24.px
        }
    root.pageHeader(tr("Helferschichten"))

    root.h2(tr("Veranstaltung")) { addCssClass("h5") }
    val eventSelectRow = root.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    val eventSelect = eventSelectRow.select(options = emptyList(), label = tr("Veranstaltung"))

    root.h2(tr("Verfügbare Schichten")) { addCssClass("h5") }
    // W5 (R34): the event list and the shift list each have a real error state with retry; the shift list is one `dataSection`.
    val eventsErrorHost = root.vPanel(spacing = 4)
    lateinit var shiftsSection: DataSection

    // Audit fix M1: without a chosen event there is NOTHING to say about shifts -- "no shifts open" would be a claim of fact (no events,
    // or the event list failed). The section only loads for a real event; the no-event case has its own text below.
    fun refreshList() {
        if (eventSelect.value != null) shiftsSection.reload()
    }
    shiftsSection =
        root.dataSection<List<EventVolunteerShiftDto>>(
            emptyText = tr("Für diese Veranstaltung sind aktuell keine Schichten offen."),
            isEmpty = { it.isEmpty() },
            load = {
                // never reached without an event ([refreshList] guards it); a missing event is a failed load, not "no shifts"
                val eventId = eventSelect.value
                if (eventId == null) {
                    null
                } else {
                    guarded { rpcService<IEventVolunteerService>().listShifts(eventId) }
                        ?.filter { it.status == EventVolunteerShiftStatus.ACTIVE }
                }
            },
            render = { panel, shifts ->
                val listPanel = panel.vPanel(spacing = 6)
                shifts.forEach { shift -> renderMyVolunteerShiftRow(listPanel, shift, ::refreshList) }
            },
        )

    fun loadEvents() {
        eventsErrorHost.removeAll()
        AppScope.launch {
            val page =
                guarded {
                    rpcService<IEventService>().listEvents(EventQuery(includePast = false, limit = 200))
                }
            if (page == null) {
                eventsErrorHost.dataErrorState(onRetry = ::loadEvents)
                return@launch
            }
            val options = page.rows.map { it.id to it.title }
            eventSelect.options = options
            if (options.isEmpty()) {
                eventsErrorHost.p(tr("Es sind derzeit keine Veranstaltungen geplant.")) { addCssClasses("text-muted") }
                return@launch
            }
            eventSelect.value = options.first().first
            refreshList()
        }
    }

    eventSelect.subscribe { refreshList() }

    loadEvents()
}

private fun renderMyVolunteerShiftRow(
    panel: SimplePanel,
    shift: EventVolunteerShiftDto,
    onChanged: () -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    headerRow.div(shift.description) { addCssClasses("flex-grow-1 fw-bold") }

    row.div(gettext("%1 – %2", shift.startsAt.toString(), shift.endsAt.toString())) { addCssClasses("text-muted small") }
    row.div(gettext("Besetzung: %1 von %2", shift.confirmedCount.toString(), shift.neededCount.toString())) {
        addCssClasses("text-muted small")
    }

    val actionRow = row.hPanel(spacing = 8)
    if (shift.ownSignupStatus == EventVolunteerSignupStatus.CONFIRMED) {
        actionRow.div(tr("Sie sind für diese Schicht angemeldet.")) { addCssClasses("text-success small flex-grow-1") }
        val cancelButton = actionRow.button(tr("Zusage zurückziehen"), style = ButtonStyle.OUTLINEDANGER)
        cancelButton.onClick {
            AppScope.launch {
                val result = guarded { rpcService<IEventVolunteerService>().cancelOwnSignup(shift.id) }
                if (result != null) {
                    notifyInfo(tr("Ihre Zusage wurde zurückgezogen."))
                    onChanged()
                }
            }
        }
    } else if (shift.full) {
        actionRow.div(tr("Diese Schicht ist bereits voll besetzt.")) { addCssClasses("text-muted small flex-grow-1") }
    } else {
        val signUpButton = actionRow.button(tr("Anmelden"), style = ButtonStyle.PRIMARY)
        signUpButton.onClick {
            signUpButton.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IEventVolunteerService>().signUpSelf(shift.id) }
                signUpButton.disabled = false
                if (result != null) {
                    notifySuccess(gettext("Sie haben sich für \"%1\" angemeldet.", shift.description))
                    onChanged()
                }
            }
        }
    }
}
