package network.lapis.cloud.client

import io.kvision.form.select.Select
import io.kvision.form.select.select
import io.kvision.html.Button
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
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import network.lapis.cloud.shared.domain.MailingListDto
import network.lapis.cloud.shared.domain.MailingListSubscriptionDto
import network.lapis.cloud.shared.domain.MailingMessageDto
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.rpc.IDirectMessageService
import network.lapis.cloud.shared.rpc.IMailingService
import network.lapis.cloud.shared.rpc.IMemberService

/**
 * Carries forward the Mailinglisten/Postfach functionality the pre-V0.7.3 demo already exercised
 * (`listMailingLists`/`subscribe`/`unsubscribe`/`unreadCount`) -- exactly the same calls, just
 * re-hosted under real session auth instead of the removed "acting as" switcher. See V0.7.3 plan
 * "Open Question 3" for why this self-service tier was carried forward as-is rather than either
 * expanded or removed at the time.
 *
 * Mail-merge/Postal-Dispatch UI wave, design decision D1: appends an ADMIN/BOARD-only mailing-list
 * *admin-authoring* section below these self-service sections -- `createMailingList`/
 * `adminSubscribeMember`/`listSubscribers`/`createDraftMessage`/`listMailingMessages`/
 * `sendMailingMessage`, all verified `BOARD_ROLES`-gated server-side (`MailingService.kt`). Exact
 * `DsgvoRightsScreen.kt` D10 idiom -- additive, never a second tab, never exclusive: self-service
 * always renders first and unconditionally, the admin block is appended only when
 * [AppState.hasRole] BOARD/ADMIN. `Routes.COMMUNICATION` stays `requireAuth` at the route level
 * (every member still needs this screen for their own self-service); the narrower BOARD/ADMIN tier
 * is gated inside the screen, same posture as `DSGVO_RIGHTS`.
 */
fun renderCommunicationScreen(container: SimplePanel) {
    val root =
        container.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 640.px
            marginTop = 24.px
        }
    root.pageHeader(tr("Kommunikation"))

    val refreshMailingLists = renderMailingLists(root)
    renderInbox(root)

    if (AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)) {
        renderMailingListAdminSection(root, refreshMailingLists)
    }
}

// ================================================================================================
// Self-service tier -- any authenticated member: list/subscribe/unsubscribe, own inbox unread count
// ================================================================================================

/**
 * Returns the section's own refresh closure so the D1 admin-authoring block below can trigger a
 * re-render of this self-service list after `createMailingList` -- same "two panels, one refresh
 * trigger" wiring `LedgerScreen.kt`'s `refreshJournalFn` establishes for the equivalent dependency.
 */
private fun renderMailingLists(root: SimplePanel): () -> Unit {
    root.h2(tr("Mailinglisten")) { addCssClass("h5") }
    val panel = root.vPanel(spacing = 4)

    fun refresh() {
        panel.removeAll()
        AppScope.launch {
            val lists = guarded { rpcService<IMailingService>().listMailingLists() } ?: return@launch
            if (lists.isEmpty()) {
                panel.p(tr("Noch keine Mailinglisten."))
                return@launch
            }
            lists.forEach { list ->
                val row = panel.hPanel(spacing = 8) { addCssClass("align-items-center") }
                row.div(gettext("%1 (%2 Abonnenten)", list.name, list.subscriberCount)) { addCssClass("flex-grow-1") }
                val toggleButton = row.button(if (list.isSubscribedByCurrentMember) tr("Abbestellen") else tr("Abonnieren"))
                toggleButton.onClick {
                    AppScope.launch {
                        val result =
                            guarded {
                                if (list.isSubscribedByCurrentMember) {
                                    rpcService<IMailingService>().unsubscribe(list.id)
                                } else {
                                    rpcService<IMailingService>().subscribe(list.id)
                                }
                            }
                        if (result != null) refresh()
                    }
                }
            }
        }
    }
    refresh()
    return ::refresh
}

private fun renderInbox(root: SimplePanel) {
    root.h2(tr("Postfach")) { addCssClass("h5") }
    val panel = root.vPanel(spacing = 4)
    AppScope.launch {
        val unread = guarded { rpcService<IDirectMessageService>().unreadCount() } ?: return@launch
        panel.div(gettext("Ungelesene Nachrichten: %1", unread))
    }
}

// ================================================================================================
// D1: admin-authoring tier -- BOARD/ADMIN only: create lists, force-subscribe members, compose and
// send messages. Never touched by a plain MEMBER -- the whole section is gated at the call site in
// [renderCommunicationScreen], not per-widget here.
// ================================================================================================

private fun renderMailingListAdminSection(
    root: SimplePanel,
    refreshSelfService: () -> Unit,
) {
    root.h2(tr("Mailinglisten verwalten")) { addCssClass("h5") }
    root.div(
        tr("Mailinglisten anlegen, Mitglieder gezielt eintragen und Nachrichten an eine Liste verschicken."),
    ) { addCssClasses("text-muted small") }

    // `refreshListSelector` breaks the same forward-reference cycle `LedgerScreen.kt`'s
    // `refreshJournalFn` does: the create-form's success callback needs to refresh the selector
    // built below it, but the selector doesn't exist yet at the point the create form is rendered.
    var refreshListSelector: ((selectId: String?) -> Unit)? = null

    renderCreateMailingListForm(root, refreshSelfService) { newListId -> refreshListSelector?.invoke(newListId) }

    refreshListSelector = renderManageListSelector(root, refreshSelfService)
}

/**
 * Inline mini-form -- name (required) + description (optional). On success, both the self-service
 * panel above (subscriber counts changed) and the "Liste verwalten" selector below need a refresh;
 * [onCreated] lets the caller re-render the selector so the new list is immediately pickable
 * without a full page reload.
 */
internal fun renderCreateMailingListForm(
    root: SimplePanel,
    refreshSelfService: () -> Unit,
    onCreated: (newListId: String) -> Unit,
) {
    val panel = root.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    panel.div(tr("Neue Mailingliste anlegen")) { addCssClass("fw-bold") }
    // Formular-Grammatik (V1.4.29, W4b): Name ist Pflicht, Beschreibung optional => Fall (a).
    val form = panel.lapisForm()
    val nameField = form.textField(label = tr("Name"), required = true)
    val descriptionField = form.textField(label = tr("Beschreibung"))
    val createButton = Button(tr("Anlegen"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = createButton)

    createButton.onClick {
        form.submit(createButton) {
            val name = nameField.value.trim()
            val description = descriptionField.value.trim().takeIf { it.isNotBlank() }
            val result = guarded { rpcService<IMailingService>().createMailingList(name, description) }
            if (result != null) {
                notifySuccess(gettext("Mailingliste \"%1\" wurde angelegt.", name))
                nameField.reset()
                descriptionField.reset()
                refreshSelfService()
                onCreated(result.id)
            }
        }
    }
}

/**
 * `select` populated from [IMailingService.listMailingLists], plus a "Verwalten" button that
 * (re-)renders the detail panel below for the chosen list. Returns its own refresh closure --
 * `(selectId) -> Unit` -- so [renderMailingListAdminSection] can re-populate the option set (and
 * pre-select a freshly created list) after `createMailingList`, without rebuilding this whole
 * section's DOM (the "two panels, one refresh trigger" wiring `LedgerScreen.kt`'s
 * `refreshJournalFn` already establishes for the same kind of cross-panel dependency).
 */
private fun renderManageListSelector(
    root: SimplePanel,
    refreshSelfService: () -> Unit,
): (selectId: String?) -> Unit {
    root.div(tr("Liste verwalten")) { addCssClasses("fw-bold mt-2") }
    val row = root.hPanel(spacing = 8) { addCssClasses("align-items-end") }
    val listSelect = row.select(options = emptyList(), label = tr("Mailingliste"))
    val manageButton = row.button(tr("Verwalten"), style = ButtonStyle.OUTLINESECONDARY)
    val detailPanel = root.vPanel(spacing = 10)

    var lists: List<MailingListDto> = emptyList()

    fun refresh(selectId: String?) {
        AppScope.launch {
            lists = guarded { rpcService<IMailingService>().listMailingLists() } ?: emptyList()
            listSelect.options = lists.map { it.id to gettext("%1 (%2 Abonnenten)", it.name, it.subscriberCount) }
            listSelect.value = selectId?.takeIf { id -> lists.any { it.id == id } } ?: lists.firstOrNull()?.id
        }
    }
    refresh(null)

    manageButton.onClick {
        val selected = lists.find { it.id == listSelect.value } ?: return@onClick
        renderMailingListDetail(detailPanel, selected, refreshSelfService)
    }

    return ::refresh
}

/**
 * Detail panel for one mailing list -- Abonnenten (read-only), Mitglied hinzufügen
 * ([IMailingService.adminSubscribeMember]), and Nachrichten (compose draft + send, D2). Re-rendered
 * from scratch on every "Verwalten" click, so no cross-list stale state can leak between selections.
 */
internal fun renderMailingListDetail(
    panel: SimplePanel,
    list: MailingListDto,
    refreshSelfService: () -> Unit,
) {
    panel.removeAll()
    val detail = panel.vPanel(spacing = 10) { addCssClasses("border rounded p-3") }
    // Security audit W6b follow-up round 3 (major finding A): mailing list name/description are admin-editable
    // free text rendered as raw widget content -- sanitize before KVision can resolve a forged marker on render.
    detail.div(sanitizeUntrustedI18nText(list.name)) { addCssClass("fw-bold") }
    list.description?.takeIf { it.isNotBlank() }?.let { description ->
        detail.div(sanitizeUntrustedI18nText(description)) { addCssClasses("text-muted small") }
    }

    // ---- Abonnenten ----------------------------------------------------------------------------
    detail.div(tr("Abonnenten")) { addCssClasses("fw-bold mt-2") }
    val subscribersPanel = detail.vPanel(spacing = 4)

    fun refreshSubscribers() {
        subscribersPanel.removeAll()
        AppScope.launch {
            val subscribers = guarded { rpcService<IMailingService>().listSubscribers(list.id) } ?: return@launch
            if (subscribers.isEmpty()) {
                subscribersPanel.p(tr("Noch keine Abonnenten."))
                return@launch
            }
            subscribers.forEach { subscriber -> renderSubscriberRow(subscribersPanel, subscriber) }
        }
    }
    refreshSubscribers()

    // ---- Mitglied hinzufügen ---------------------------------------------------------------------
    detail.div(tr("Mitglied hinzufügen")) { addCssClasses("fw-bold mt-2") }
    val addForm = detail.lapisForm()
    val addRow = addForm.panel.hPanel(spacing = 8) { addCssClasses("align-items-end") }
    val memberField =
        addForm.selectField(
            label = tr("Mitglied"),
            options = listOf("" to gettext("— bitte wählen —")),
            value = "",
            required = true,
            host = addRow,
            slotHost = addForm.panel, // Fehlerslot UNTER die Zeile, nicht als Flex-Element zwischen Feld und Knopf
            requiredMessage = gettext("Bitte ein Mitglied auswählen."),
        )
    val memberSelect = memberField.control as Select
    val addButton = addRow.button(tr("Hinzufügen"), style = ButtonStyle.OUTLINEPRIMARY)
    addForm.finish()
    AppScope.launch {
        val members = guarded { rpcService<IMemberService>().listMembers() } ?: emptyList()
        // Keine Vorauswahl: ein einzelner Klick auf "Hinzufügen" darf nie ein Mitglied eintragen, das niemand gewählt hat
        // (personenbezogene Schreiboperation). Der leere Platzhalter bleibt gewählt; required lässt ihn nicht durch.
        memberSelect.options = listOf("" to gettext("— bitte wählen —")) + untrustedOptions(members.map { it.id to it.displayName })
        memberField.setValue("")
        memberField.validate(force = false)
    }
    addButton.onClick {
        addForm.submit(addButton) {
            val result = guarded { rpcService<IMailingService>().adminSubscribeMember(list.id, memberField.value) }
            if (result != null) {
                notifySuccess(tr("Mitglied wurde eingetragen."))
                refreshSubscribers()
                refreshSelfService()
            }
        }
    }

    // ---- Nachrichten -------------------------------------------------------------------------
    detail.div(tr("Nachrichten")) { addCssClasses("fw-bold mt-2") }
    val composePanel = detail.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    // Zwei Pflichtfelder => Fall (c): kein Stern, keine Legende.
    val composeForm = composePanel.lapisForm()
    val subjectField = composeForm.textField(label = tr("Betreff"), required = true)
    val bodyField = composeForm.textAreaField(label = tr("Text"), rows = 4, required = true)
    val draftButton = Button(tr("Als Entwurf speichern"), style = ButtonStyle.OUTLINEPRIMARY)
    composeForm.buttons(primary = draftButton)
    // Welle V1.9.7 "SuperMailer" D4: the honesty caption only makes sense in LOG delivery mode --
    // in SMTP mode, sendMailingMessage now genuinely calls a real transport (MailingDeliveryWorker),
    // so showing the old "this is only a log entry" text would be actively misleading. Fetched
    // once per detail render; see MAILING_SEND_STUB_CAPTION KDoc for the caption text itself.
    val deliveryModeCaption = composePanel.div("") { addCssClasses("text-muted small") }
    AppScope.launch {
        val mode = guarded { rpcService<IMailingService>().getMailingDeliveryMode() }
        deliveryModeCaption.content = if (mode == MailingDeliveryMode.LOG) tr(MAILING_SEND_STUB_CAPTION) else ""
    }

    val messagesPanel = detail.vPanel(spacing = 6)

    fun refreshMessages() {
        messagesPanel.removeAll()
        AppScope.launch {
            val messages = guarded { rpcService<IMailingService>().listMailingMessages(list.id) } ?: return@launch
            if (messages.isEmpty()) {
                messagesPanel.p(tr("Noch keine Nachrichten."))
                return@launch
            }
            messages.forEach { message -> renderMailingMessageRow(messagesPanel, message, list.name, ::refreshMessages) }
        }
    }
    refreshMessages()

    draftButton.onClick {
        composeForm.submit(draftButton) {
            val subject = subjectField.value.trim()
            val result = guarded { rpcService<IMailingService>().createDraftMessage(list.id, subject, bodyField.value.trim()) }
            if (result != null) {
                notifySuccess(gettext("Entwurf \"%1\" wurde gespeichert.", subject))
                subjectField.reset()
                bodyField.reset()
                refreshMessages()
            }
        }
    }
}

private fun renderSubscriberRow(
    panel: SimplePanel,
    subscriber: MailingListSubscriptionDto,
) {
    val row = panel.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    row.untrustedDiv(subscriber.memberDisplayName, className = "flex-grow-1")
    val statusText =
        if (subscriber.unsubscribedAt != null) {
            gettext("Abbestellt am %1", formatDateTime(subscriber.unsubscribedAt!!))
        } else {
            gettext("Abonniert seit %1", formatDateTime(subscriber.subscribedAt))
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
private fun renderMailingMessageRow(
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
        row.div(gettext("Gesendet am %1", formatDateTime(sentAt))) { addCssClasses("text-muted small") }
    }

    if (message.status == MailingMessageStatus.DRAFT) {
        val sendButton = row.button(tr("Senden"), style = ButtonStyle.OUTLINEDANGER)
        sendButton.onClick {
            confirmDialog(
                title = tr("Nachricht senden"),
                message =
                    gettext(
                        "Die Nachricht \"%1\" wird an alle aktiven Abonnenten der " +
                            "Mailingliste \"%2\" verschickt. Dieser Schritt kann nicht rückgängig gemacht werden.",
                        message.subject,
                        listName,
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

// ================================================================================================
// Pure helpers -- covered by CommunicationScreenTest.kt
// ================================================================================================

/**
 * Review fix (finding #6, W-SuperMailer round 1): [MailingMessageStatus.QUEUED] is no longer a
 * dead branch -- since the V1.9.7 async-send rewrite, `MailingService.sendMailingMessage` writes
 * exactly this status (a bounded `DRAFT -> QUEUED` transition, see its KDoc) and every message sits
 * in it for the whole time `MailingDeliveryWorker` is still working through its recipients, which
 * can be minutes for a large list.
 */
fun mailingMessageStatusLabel(status: MailingMessageStatus): String =
    when (status) {
        MailingMessageStatus.DRAFT -> gettext("Entwurf")
        MailingMessageStatus.SENT -> gettext("Gesendet")
        MailingMessageStatus.QUEUED -> gettext("In Warteschlange")
        MailingMessageStatus.FAILED -> gettext("Fehlgeschlagen")
    }

fun mailingMessageStatusColor(status: MailingMessageStatus): String =
    when (status) {
        MailingMessageStatus.DRAFT -> "warning"
        MailingMessageStatus.SENT -> "success"
        MailingMessageStatus.QUEUED -> "secondary"
        MailingMessageStatus.FAILED -> "danger"
    }

/**
 * Honesty caption, shown directly under the compose form's "Als Entwurf speichern" button --
 * **only while [IMailingService.getMailingDeliveryMode] is `LOG`** (see the fetch-and-conditionally-
 * render call site in [renderMailingListDetail]). Welle V1.9.7 "SuperMailer" replaced the old
 * always-synchronous, always-simulated send (`MailingService.kt`'s previous `runCatching {
 * DeliveryStatus.SENT }`, which never called any transport and could double-send on a repeat
 * click) with a genuine async delivery worker -- in `LOG` mode (the server default,
 * `LAPIS_MAILING_DELIVERY` unset), the full pipeline still runs (sanitize/render/queue/deliver)
 * but the actual transport call is skipped, so this caption remains accurate for that mode. In
 * `smtp` mode, real mail goes out and this caption is hidden entirely -- showing it there would be
 * actively misleading. Same honesty posture as `DsgvoRightsScreen.kt`'s
 * `ERASURE_SELF_STATUS_VISIBILITY_CAPTION`.
 */
const val MAILING_SEND_STUB_CAPTION =
    "Der Versand ist in dieser Version ein interner Protokolleintrag -- es wird noch keine echte " +
        "E-Mail über einen externen Versanddienst verschickt. Jeder aktive Abonnent erhält einen " +
        "Eintrag mit Status \"Gesendet\" im Systemprotokoll, keine tatsächliche Zustellung."
