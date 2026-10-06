package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import network.lapis.cloud.shared.domain.WebhookDeliveryDto
import network.lapis.cloud.shared.rpc.IWebhookService
import kotlin.time.Clock

/**
 * Welle V1.3.2 "Webhooks" (ausgehend), Design-Team decision D5 -- collapsible (default CLOSED)
 * delivery-history table embedded inside a webhook's own card in `ApiKeysScreen.kt`. 25
 * entries/page (no explicit pager UI this wave -- this list is diagnostic, not a primary work
 * queue, so a simple most-recent-25 view is enough for now). Columns: Zeitpunkt (absolut) · Event
 * · Versuch n/6 · HTTP · Status · Nächster Versuch (relativ). A non-null `lastErrorCode` renders as
 * a second, indented line inside the Status cell.
 */
fun SimplePanel.renderWebhookDeliveryLogPanel(apiKeyId: String): SimplePanel {
    val wrapper = vPanel(spacing = 4) { addCssClasses("mt-2") }
    val toggleButton = wrapper.button(tr("Zustellungsprotokoll anzeigen"), style = ButtonStyle.LINK)
    val body = wrapper.vPanel(spacing = 4) { hide() }

    var loaded = false
    toggleButton.onClick {
        if (body.visible) body.hide() else body.show()
        toggleButton.text = if (body.visible) tr("Zustellungsprotokoll ausblenden") else tr("Zustellungsprotokoll anzeigen")
        if (body.visible && !loaded) {
            loaded = true
            loadWebhookDeliveryLog(body, apiKeyId)
        }
    }
    return wrapper
}

private fun loadWebhookDeliveryLog(
    body: SimplePanel,
    apiKeyId: String,
) {
    body.removeAll()
    body.div(tr("Wird geladen …")) { addCssClasses("text-muted small") }
    AppScope.launch {
        val page =
            webhookGuarded(conflictMessage = tr(WEBHOOK_DELIVERIES_CONFLICT_MESSAGE)) {
                rpcService<IWebhookService>().listWebhookDeliveries(apiKeyId = apiKeyId, limit = 25, offset = 0)
            }
        body.removeAll()
        if (page == null) return@launch
        renderWebhookDeliveryTable(body, page.items)
        body.div(tr("Einträge werden nach 30 Tagen automatisch gelöscht.")) { addCssClasses("small text-muted mt-1") }
    }
}

private fun renderWebhookDeliveryTable(
    body: SimplePanel,
    items: List<WebhookDeliveryDto>,
) {
    if (items.isEmpty()) {
        body.div(tr("Noch keine Zustellungen.")) { addCssClasses("text-muted small") }
        return
    }
    // V1.9.68 (R59, one scroll surface): a `dataTable` (card list on a narrow screen) instead of a table inside an
    // `overflow: auto` wrapper -- the log grows with its rows, the page scrolls.
    body.plainDataTable(
        columns =
            listOf(
                textColumn<WebhookDeliveryDto>(title = tr("Zeitpunkt"), primary = true) { formatSystemTimestamp(it.occurredAt) },
                DataColumn(title = tr("Event"), cell = { cell, item -> cell.webhookEventTypeBadge(item.eventType) }),
                textColumn<WebhookDeliveryDto>(title = tr("Versuch"), numeric = true) { "${it.attemptCount}/${it.maxAttempts}" },
                textColumn<WebhookDeliveryDto>(title = "HTTP", numeric = true) { it.lastHttpStatus?.toString() ?: "–" },
                DataColumn(
                    title = tr("Status"),
                    cell = { cell, item ->
                        cell.webhookDeliveryStatusBadge(item.status)
                        val errorCode = item.lastErrorCode
                        if (errorCode != null) {
                            cell.div(webhookFailureReasonLabel(errorCode)) { addCssClasses("small text-muted mt-1") }
                        }
                    },
                ),
                // Trusted: the relative text is built from `tr(...)`/`gettext(...)` and numbers only (no foreign field).
                textColumn<WebhookDeliveryDto>(title = tr("Nächster Versuch")) {
                    it.nextAttemptAt?.let { next -> trusted(formatWebhookRelativeFuture(next)) } ?: "–"
                },
            ),
        rows = items,
    )
}

/**
 * Coarse "in N Min./Std." relative rendering for [WebhookDeliveryDto.nextAttemptAt] -- deliberately
 * simple (minute/hour buckets only, no localized relative-time library pulled in for this one
 * field) since the retry window this project's own backoff plan uses tops out at 3h.
 */
private fun formatWebhookRelativeFuture(target: LocalDateTime): String {
    val targetInstant = target.toInstant(TimeZone.UTC)
    val now = Clock.System.now()
    val deltaSeconds = (targetInstant - now).inWholeSeconds
    if (deltaSeconds <= 0) return tr("in Kürze")
    val minutes = deltaSeconds / 60
    return when {
        minutes < 1 -> tr("in Kürze")
        minutes < 60 -> gettext("in %1 Min.", minutes.toString())
        else -> gettext("in %1 Std.", (minutes / 60).toString())
    }
}
