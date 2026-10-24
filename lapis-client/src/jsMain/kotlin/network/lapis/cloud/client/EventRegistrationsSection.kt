package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.Button
import io.kvision.html.Link
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import io.kvision.table.Cell
import io.kvision.table.HeaderCell
import io.kvision.table.Scope
import io.kvision.table.cell
import io.kvision.table.row
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventRegistrationDto
import network.lapis.cloud.shared.domain.EventRegistrationOverviewDto
import network.lapis.cloud.shared.domain.EventRegistrationPaymentState
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.rpc.IEventService

/*
 * V1.9.204 -- "Anmeldungen" on the event detail page (BOARD/ADMIN only): the counters, a filter, the table with the registrant message folded
 * out per row, and the CSV export. The counters and the effective status of every row come from the server (`getRegistrationOverview`); the
 * filter only hides rows that are already loaded (no second request). The export is ALWAYS the whole list, whatever the filter shows.
 * The message of a registrant is untrusted free text: it is set as element text ([untrustedDiv]), never as HTML.
 */

private external fun encodeURIComponent(value: String): String

/** The URL of the one raw GET route of the registration export (same-origin, `Content-Disposition: attachment`). */
object EventRegistrationsHttp {
    fun csvUrl(eventId: String): String = "/api/events/${encodeURIComponent(eventId)}/registrations.csv"
}

/** The filter of the registration table. */
internal enum class RegistrationFilter {
    ALL,
    ACTIVE,
    WAITLIST,
    INACTIVE,
    WITH_MESSAGE,
    ;

    fun label(): String =
        when (this) {
            ALL -> gettext("Alle")
            ACTIVE -> gettext("Angemeldet")
            WAITLIST -> gettext("Warteliste")
            INACTIVE -> gettext("Abgesagt/abgelaufen")
            WITH_MESSAGE -> gettext("Mit Nachricht")
        }

    fun keeps(row: EventRegistrationDto): Boolean =
        when (this) {
            ALL -> true
            ACTIVE -> row.status == EventRegistrationStatus.CONFIRMED || row.status == EventRegistrationStatus.PENDING_PAYMENT
            WAITLIST -> row.status == EventRegistrationStatus.WAITLISTED
            INACTIVE -> row.status == EventRegistrationStatus.CANCELLED || row.status == EventRegistrationStatus.EXPIRED
            WITH_MESSAGE -> row.message != null
        }
}

internal fun registrationStatusLabel(status: EventRegistrationStatus): String =
    when (status) {
        EventRegistrationStatus.CONFIRMED -> gettext("Angemeldet")
        EventRegistrationStatus.PENDING_PAYMENT -> gettext("Zahlung offen")
        EventRegistrationStatus.WAITLISTED -> gettext("Warteliste")
        EventRegistrationStatus.CANCELLED -> gettext("Abgesagt")
        EventRegistrationStatus.EXPIRED -> gettext("Abgelaufen")
    }

private fun registrationStatusColor(status: EventRegistrationStatus): String =
    when (status) {
        EventRegistrationStatus.CONFIRMED -> "success"
        EventRegistrationStatus.PENDING_PAYMENT -> "warning"
        EventRegistrationStatus.WAITLISTED -> "info"
        EventRegistrationStatus.CANCELLED -> "secondary"
        EventRegistrationStatus.EXPIRED -> "secondary"
    }

internal fun registrationPaymentLabel(state: EventRegistrationPaymentState?): String =
    when (state) {
        EventRegistrationPaymentState.FREE, null -> ""
        EventRegistrationPaymentState.OPEN -> gettext("Offen")
        EventRegistrationPaymentState.PAID -> gettext("Bezahlt")
        EventRegistrationPaymentState.REFUNDED -> gettext("Erstattet")
        EventRegistrationPaymentState.UNPAID -> gettext("Nicht bezahlt")
    }

/** The counter line, e.g. "Angemeldet 23 · Warteliste 2 · Abgesagt/abgelaufen 4 · Freie Plätze 17" (the last part only with a capacity). */
internal fun registrationCountsText(overview: EventRegistrationOverviewDto): String {
    val base =
        gettext(
            "Angemeldet %1 · Warteliste %2 · Abgesagt/abgelaufen %3",
            overview.activeCount.toString(),
            overview.waitlistedCount.toString(),
            overview.inactiveCount.toString(),
        )
    val free = overview.freeSeats ?: return base
    return base + " · " + gettext("Freie Plätze %1", free.toString())
}

/**
 * Adds the section to [parent]. Does nothing (and asks nothing) for anybody but BOARD/ADMIN: the RPC is not called, so a plain member's
 * page never even triggers a refusal. [load] is replaceable for tests.
 */
internal fun renderEventRegistrationsSection(
    parent: SimplePanel,
    eventId: String,
    load: suspend () -> EventRegistrationOverviewDto? = { guarded { rpcService<IEventService>().getRegistrationOverview(eventId) } },
) {
    if (!AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)) return
    val section = parent.vPanel(spacing = 6)
    section.h2(tr("Anmeldungen")) { addCssClass("h5") }
    val body = section.vPanel(spacing = 6)
    lateinit var data: DataSection
    data =
        body.dataSection<EventRegistrationOverviewDto>(
            emptyText = gettext("Noch keine Anmeldungen."),
            isEmpty = { it.rows.isEmpty() },
            load = load,
            render = { panel, overview -> renderRegistrationOverview(panel, eventId, overview) },
        )
    data.reload()
}

private fun renderRegistrationOverview(
    panel: SimplePanel,
    eventId: String,
    overview: EventRegistrationOverviewDto,
) {
    panel.div(registrationCountsText(overview)) { addCssClasses("lapis-registration-counts text-muted small") }
    var filter = RegistrationFilter.ALL
    val toolbar = panel.lapisToolbar()
    // The export is a plain same-origin download of the WHOLE list; the filter above does not change it.
    toolbar.add(
        Link(label = tr("Alle Anmeldungen als CSV"), url = EventRegistrationsHttp.csvUrl(eventId)).apply {
            setAttribute("target", "_blank")
            setAttribute("rel", "noopener")
            addCssClasses("btn btn-outline-secondary")
        },
    )
    if (overview.truncated) {
        panel.p(tr("Es werden nur die ersten 2000 Anmeldungen angezeigt. Die CSV enthält alle.")) { addCssClasses("text-muted small") }
    }
    val tableHost = panel.vPanel(spacing = 4)

    fun rebuild() {
        tableHost.removeAll()
        val rows = overview.rows.filter { filter.keeps(it) }
        if (rows.isEmpty()) {
            tableHost.p(tr("Keine Anmeldungen in dieser Auswahl.")) { addCssClasses("text-muted") }
            return
        }
        renderRegistrationTable(tableHost, rows)
    }
    toolbar.segmentedRadioGroup(
        options = RegistrationFilter.entries.map { SegmentOption(value = it, label = it.label()) },
        selected = filter,
        ariaLabel = tr("Anzeigen"),
    ) { chosen ->
        filter = chosen
        rebuild()
    }
    rebuild()
}

private fun renderRegistrationTable(
    host: Container,
    rows: List<EventRegistrationDto>,
) {
    val headers =
        listOf(
            TableHeader(tr("Name")),
            TableHeader(tr("Art")),
            TableHeader(tr("Status")),
            TableHeader(tr("Wartelistenplatz"), numeric = true),
            TableHeader(tr("Angemeldet am")),
            TableHeader(tr("Gebühr")),
            TableHeader(tr("E-Mail (Gast)")),
            TableHeader(tr("Nachricht")),
        )
    val table = host.masterTable(tr("Anmeldungen"), headers)
    rows.forEach { reg ->
        val name = reg.memberDisplayName ?: reg.guestName.orEmpty()
        var toggle: Button? = null
        table.row {
            masterRow()
            val nameCell = HeaderCell(scope = Scope.ROW) { addCssClass("fw-normal") }
            add(nameCell)
            nameCell.cellText(name)
            if (reg.memberId != null) textCell(trusted(tr("Mitglied"))) else textCell(trusted(tr("Gast")))
            cell { statusBadge(registrationStatusLabel(reg.status), registrationStatusColor(reg.status)) }
            numCell(reg.waitlistPosition?.toString() ?: "–")
            textCell(formatSystemTimestamp(reg.registeredAt))
            cell { feeCell(reg) }
            textCell(reg.guestEmail ?: "–")
            if (reg.message == null) {
                textCell("–")
            } else {
                cell {
                    toggle =
                        tableActionGroup().tableActionButton(
                            ActionIcon.MESSAGES,
                            gettext("Nachricht anzeigen: %1", sanitizeUntrustedI18nText(name)),
                        )
                }
            }
        }
        val message = reg.message
        val button = toggle
        if (message != null && button != null) {
            // The detail row follows its data row from the start (hidden with `d-none`), so `aria-controls` always names a real element.
            val detailId = "lapis-registration-message-${reg.id}"
            val detail =
                table.row {
                    id = detailId
                    addCssClass("d-none")
                    cell {
                        setAttribute("colspan", "8")
                        untrustedDiv(message, className = "lapis-registration-message")
                    }
                }
            button.setAttribute("aria-expanded", "false")
            button.setAttribute("aria-controls", detailId)
            var open = false
            button.onClick {
                open = !open
                if (open) detail.removeCssClass("d-none") else detail.addCssClass("d-none")
                button.setAttribute("aria-expanded", open.toString())
            }
        }
    }
}

private fun Cell.feeCell(reg: EventRegistrationDto) {
    if (reg.feeAmount.toDouble() == 0.0) {
        span("–")
        return
    }
    moneySpan(reg.feeAmount)
    val state = registrationPaymentLabel(reg.paymentState)
    if (state.isNotEmpty()) {
        span(" ")
        span(state) { addCssClasses("text-muted small") }
    }
}
