package network.lapis.cloud.client

import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import network.lapis.cloud.shared.domain.MailDeliveryStatusDto
import network.lapis.cloud.shared.rpc.IMailDeliveryStatusService

/**
 * Welle V1.9.81 -- the ADMIN health card of the mail pipeline ("E-Mail-Versand", sidebar group System): how much of the hourly send budget
 * is used, how many system mails wait in the durable queue and for how long, and which kinds of mail failed or expired in the last 7 days.
 *
 * Counts and the closed purpose vocabulary only -- the server never sends an address, a subject, or a per-row instant, so there is
 * nothing here that could leak one. The route AND the server (`MailDeliveryStatusService`, first statement) require ADMIN.
 */
fun renderMailDeliveryStatusScreen(container: SimplePanel) = renderMailDeliveryStatusScreen(container) { fetchMailDeliveryStatus() }

private suspend fun fetchMailDeliveryStatus(): MailDeliveryStatusDto? =
    guarded {
        rpcService<IMailDeliveryStatusService>().getMailDeliveryStatus()
    }

internal fun renderMailDeliveryStatusScreen(
    container: SimplePanel,
    load: suspend () -> MailDeliveryStatusDto?,
) {
    val root = container.dataScreenRoot()
    val header = root.pageHeader(tr("E-Mail-Versand"))
    val refresh = header.actionSlot.actionButton(ActionIcon.REFRESH, tr("Aktualisieren"))
    root.div(
        tr(
            "Zustand des E-Mail-Versands: Stundenbudget, wartende System-E-Mails und Fehlschläge der letzten 7 Tage. " +
                "Es werden nur Zahlen angezeigt, nie Adressen oder Inhalte.",
        ),
        className = "text-muted small",
    )
    // One shared load lifecycle (loading text, error with "Erneut versuchen", content): the card is always "full" -- even a quiet instance has
    // a budget line and a queue line -- so there is no empty state.
    val section =
        root.dataSection<MailDeliveryStatusDto>(
            isEmpty = { false },
            onSettled = { refresh.disabled = false },
            load = load,
            render = { body, status -> renderMailDeliveryStatusBody(body, status) },
        )
    refresh.onClick {
        refresh.disabled = true
        section.reload()
    }
    section.reload()
}

/** The card sections for [status]; `internal` so the DOM test can render it from a plain DTO. */
internal fun renderMailDeliveryStatusBody(
    body: SimplePanel,
    status: MailDeliveryStatusDto,
) {
    body.statusSection(tr("Stundenbudget")) {
        if (status.budgetEnabled) {
            div(
                gettext("%1 / %2 (Reserve %3)", status.usedInWindow ?: 0, status.maxPerHour ?: 0, status.reservePerHour ?: 0),
            ) { addCssClass("fw-bold") }
            div(tr("Gezählt werden die E-Mails der letzten 60 Minuten."), className = "text-muted small")
        } else {
            div(tr("Kein Stundenbudget eingerichtet."))
        }
        status.bulkPausedUntil?.let {
            div(
                gettext("Rundschreiben pausiert bis ca. %1 (Anbieter hat verzögert)", mailingClockTime(it)),
                className = "text-warning-emphasis",
            )
        }
    }
    body.statusSection(tr("Warteschlange")) {
        if (status.outboxEnabled) {
            div(gettext("Wartend: %1", status.queuedCount)) { addCssClass("fw-bold") }
            status.oldestQueuedAgeSeconds?.let { div(gettext("Älteste Wartezeit: %1", formatMailingDuration(it))) }
        } else {
            div(tr("Dauerhafte Warteschlange nicht aktiv (kein Verschlüsselungsschlüssel eingerichtet)."))
        }
    }
    body.statusSection(tr("Fehlgeschlagen (7 Tage)")) { purposeCounts(status.failedLast7DaysByPurpose) }
    body.statusSection(tr("Abgelaufen, ohne gesendet zu werden (7 Tage)")) { purposeCounts(status.expiredLast7DaysByPurpose) }
}

private fun SimplePanel.statusSection(
    title: String,
    content: Div.() -> Unit,
) {
    val card = div(className = "border rounded p-3 d-flex flex-column gap-1")
    card.div(title, className = "text-muted small text-uppercase")
    card.content()
}

private fun Div.purposeCounts(counts: Map<String, Int>) {
    if (counts.isEmpty()) {
        div(tr("Keine."))
        return
    }
    counts.entries.sortedBy { it.key }.forEach { (purpose, count) ->
        // The purpose is a closed, server-defined vocabulary -- still run through the untrusted-text sanitizer like any server string.
        untrustedDiv("$purpose: $count")
    }
}
