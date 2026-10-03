package network.lapis.cloud.client

import io.kvision.html.ButtonSize
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.MailingListSubscriptionDto
import network.lapis.cloud.shared.domain.MailingMessageDto
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.rpc.IMailingService

// V1.9.49: the subscriber row and the message row of the mailing-list detail moved out of `CommunicationScreen.kt` unchanged (the screen
// grew past 650 lines). They only write (sendMailingMessage) or render what the detail hands them.

internal fun renderSubscriberRow(
    panel: SimplePanel,
    subscriber: MailingListSubscriptionDto,
) {
    val row = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    row.untrustedDiv(subscriber.memberDisplayName, className = "flex-grow-1")
    val statusText =
        if (subscriber.unsubscribedAt != null) {
            gettext("Abbestellt am %1", formatSystemDateTime(subscriber.unsubscribedAt!!))
        } else {
            gettext("Abonniert seit %1", formatSystemDateTime(subscriber.subscribedAt))
        }
    row.div(statusText) { addCssClasses("text-muted small") }
}

/**
 * D2: `sendMailingMessage` is irreversible in the sense that it flips the message's status and
 * writes one delivery-log row per active subscriber -- moderate-rigor `confirmDialog` (not a bespoke
 * `Modal`), matching the tier `ContributionsScreen`'s "Erlassen"/`LedgerScreen`'s account-deactivate
 * already use, per the design review's explicit call that this carries none of postal dispatch's
 * real-cost/real-external-party stakes (see `PostalMailScreen.kt`'s bespoke `Modal` tier for that
 * comparison).
 */
internal fun renderMailingMessageRow(
    panel: SimplePanel,
    message: MailingMessageDto,
    listName: String,
    onChanged: () -> Unit,
) {
    val row = panel.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    val headerRow = row.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    // Security audit W6b follow-up round 3 (major finding A): a message subject is sender-controlled free text
    // rendered as raw widget content -- sanitize before KVision can resolve a forged marker on render.
    headerRow.div(sanitizeUntrustedI18nText(message.subject)) { addCssClass("flex-grow-1") }
    headerRow.statusBadge(mailingMessageStatusLabel(message.status), mailingMessageStatusColor(message.status))
    message.sentAt?.let { sentAt ->
        row.div(gettext("Gesendet am %1", formatSystemDateTime(sentAt))) { addCssClasses("text-muted small") }
    }

    if (message.status == MailingMessageStatus.SENT) {
        val statsHost = row.vPanel(spacing = 4)
        statsHost.hide()
        val statsButton =
            row.button(tr("Statistik"), icon = "fas fa-chart-simple", style = ButtonStyle.OUTLINESECONDARY) {
                size = ButtonSize.SMALL
            }
        statsButton.onClick {
            if (statsHost.visible) {
                statsHost.hide()
            } else {
                runGuardedAction(statsButton) {
                    val stats = guarded { rpcService<IMailingService>().mailingMessageStats(message.id) } ?: return@runGuardedAction
                    statsHost.removeAll()
                    renderMailingStatsPanel(statsHost, stats)
                    statsHost.show()
                }
            }
        }
    }

    if (message.status == MailingMessageStatus.DRAFT) {
        val sendButton = row.actionButton(ActionIcon.SEND, tr("Senden"), style = ButtonStyle.OUTLINEDANGER)
        sendButton.onClick {
            confirmDialog(
                title = tr("Nachricht senden"),
                // Security audit follow-up (untrusted-text sanitization gaps): message.subject and listName are
                // sender-/admin-editable free text composed into this gettext(...) string, which confirmDialog
                // hands straight to modal.div(message) -- sanitize the whole composed result.
                message =
                    sanitizeUntrustedI18nText(
                        gettext(
                            "Die Nachricht \"%1\" wird an alle aktiven Abonnenten der " +
                                "Mailingliste \"%2\" verschickt. Dieser Schritt kann nicht rückgängig gemacht werden.",
                            message.subject,
                            listName,
                        ),
                    ),
                confirmLabel = tr("Senden"),
            ) {
                sendButton.disabled = true
                AppScope.launch {
                    val result = guarded { rpcService<IMailingService>().sendMailingMessage(message.id) }
                    sendButton.disabled = false
                    if (result != null) {
                        // Review fix (finding #6, W-SuperMailer round 1): sendMailingMessage only
                        // QUEUES the message now (V1.9.7 async rewrite) -- the actual send happens
                        // later, off this RPC call, and in `smtp` mode can take minutes and can end
                        // FAILED. "wurde gesendet" (has been sent) overclaims what just happened.
                        notifySuccess(gettext("Nachricht \"%1\" wurde in die Versand-Warteschlange gestellt.", message.subject))
                        onChanged()
                    }
                }
            }
        }
    }
}
