package network.lapis.cloud.client

import io.kvision.form.select.select
import io.kvision.form.text.text
import io.kvision.form.text.textArea
import io.kvision.html.ButtonStyle
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.html.h1
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
            addCssClass("mx-auto")
            width = 640.px
            marginTop = 24.px
        }
    root.h1(tr("Kommunikation"))

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
    root.h2(tr("Mailinglisten"))
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
    root.h2(tr("Postfach"))
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
    root.h2(tr("Mailinglisten verwalten"))
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
private fun renderCreateMailingListForm(
    root: SimplePanel,
    refreshSelfService: () -> Unit,
    onCreated: (newListId: String) -> Unit,
) {
    val panel = root.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    panel.div(tr("Neue Mailingliste anlegen")) { addCssClass("fw-bold") }
    val nameInput = panel.text(label = tr("Name"))
    val descriptionInput = panel.text(label = tr("Beschreibung (optional)"))
    val errorBox =
        panel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val createButton = panel.button(tr("Anlegen"), style = ButtonStyle.PRIMARY)

    createButton.onClick {
        errorBox.hide()
        val name = nameInput.value.orEmpty().trim()
        val description = descriptionInput.value?.trim()?.takeIf { it.isNotBlank() }

        if (!Validation.isNonBlank(name)) {
            errorBox.content = tr("Bitte einen Namen angeben.")
            errorBox.show()
            return@onClick
        }

        createButton.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IMailingService>().createMailingList(name, description) }
            createButton.disabled = false
            if (result != null) {
                notifySuccess(gettext("Mailingliste \"%1\" wurde angelegt.", name))
                nameInput.value = null
                descriptionInput.value = null
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
private fun renderMailingListDetail(
    panel: SimplePanel,
    list: MailingListDto,
    refreshSelfService: () -> Unit,
) {
    panel.removeAll()
    val detail = panel.vPanel(spacing = 10) { addCssClasses("border rounded p-3") }
    detail.div(list.name) { addCssClass("fw-bold") }
    list.description?.takeIf { it.isNotBlank() }?.let { description ->
        detail.div(description) { addCssClasses("text-muted small") }
    }

    // ---- Abonnenten ----------------------------------------------------------------------------
    detail.div(tr("Abonnenten")) { addCssClasses("fw-bold mt-2") }
    val subscribersPanel = detail.vPanel(spacing = 4)

    fun refreshSubscribers() {
        subscribersPanel.removeAll()
        AppScope.launch {
            val subscribers = guarded { rpcService<IMailingService>().listSubscribers(list.id) } ?: return@launch
            if (subscribers.isEmpty()) {
                subscribersPanel.p(tr("Noch keine Abonnentinnen und Abonnenten."))
                return@launch
            }
            subscribers.forEach { subscriber -> renderSubscriberRow(subscribersPanel, subscriber) }
        }
    }
    refreshSubscribers()

    // ---- Mitglied hinzufügen ---------------------------------------------------------------------
    detail.div(tr("Mitglied hinzufügen")) { addCssClasses("fw-bold mt-2") }
    val addRow = detail.hPanel(spacing = 8) { addCssClasses("align-items-end") }
    val memberSelect = addRow.select(options = emptyList(), label = tr("Mitglied"))
    val addButton = addRow.button(tr("Hinzufügen"), style = ButtonStyle.OUTLINEPRIMARY)
    AppScope.launch {
        val members = guarded { rpcService<IMemberService>().listMembers() } ?: emptyList()
        memberSelect.options = members.map { it.id to it.displayName }
    }
    addButton.onClick {
        val memberId = memberSelect.value ?: return@onClick
        addButton.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IMailingService>().adminSubscribeMember(list.id, memberId) }
            addButton.disabled = false
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
    val subjectInput = composePanel.text(label = tr("Betreff"))
    val bodyInput = composePanel.textArea(label = tr("Text"), rows = 4)
    val composeErrorBox =
        composePanel.div().apply {
            addCssClass("text-danger")
            hide()
        }
    val draftButton = composePanel.button(tr("Als Entwurf speichern"), style = ButtonStyle.OUTLINEPRIMARY)
    // D2: permanent, always-visible honesty caption -- not conditional on having just sent a
    // message. See MAILING_SEND_STUB_CAPTION KDoc.
    composePanel.div(tr(MAILING_SEND_STUB_CAPTION)) { addCssClasses("text-muted small") }

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
        composeErrorBox.hide()
        val subject = subjectInput.value.orEmpty().trim()
        val bodyText = bodyInput.value.orEmpty().trim()

        if (!Validation.isNonBlank(subject) || !Validation.isNonBlank(bodyText)) {
            composeErrorBox.content = tr("Bitte Betreff und Text angeben.")
            composeErrorBox.show()
            return@onClick
        }

        draftButton.disabled = true
        AppScope.launch {
            val result = guarded { rpcService<IMailingService>().createDraftMessage(list.id, subject, bodyText) }
            draftButton.disabled = false
            if (result != null) {
                notifySuccess(gettext("Entwurf \"%1\" wurde gespeichert.", subject))
                subjectInput.value = null
                bodyInput.value = null
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
    row.div(subscriber.memberDisplayName) { addCssClass("flex-grow-1") }
    val statusText =
        if (subscriber.unsubscribedAt != null) {
            gettext("Abbestellt am %1", subscriber.unsubscribedAt)
        } else {
            gettext("Abonniert seit %1", subscriber.subscribedAt)
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
    headerRow.div(message.subject) { addCssClass("flex-grow-1") }
    headerRow.statusBadge(mailingMessageStatusLabel(message.status), mailingMessageStatusColor(message.status))
    message.sentAt?.let { sentAt -> row.div(gettext("Gesendet am %1", sentAt)) { addCssClasses("text-muted small") } }

    if (message.status == MailingMessageStatus.DRAFT) {
        val sendButton = row.button(tr("Senden"), style = ButtonStyle.OUTLINEDANGER)
        sendButton.onClick {
            confirmDialog(
                title = tr("Nachricht senden"),
                message =
                    gettext(
                        "Die Nachricht \"%1\" wird an alle aktiven Abonnentinnen und Abonnenten der " +
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
                        notifySuccess(gettext("Nachricht \"%1\" wurde gesendet.", message.subject))
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
 * [MailingMessageStatus.QUEUED] is never written by any code path today
 * (`MailingService.sendMailingMessage` writes `DRAFT` then jumps straight to `SENT` in one
 * synchronous loop, no intermediate queued state) -- reserved for a future async/webhook follow-up
 * per [IMailingService.sendMailingMessage] KDoc. Kept and labeled rather than omitted as
 * unreachable, same posture `DsgvoRightsScreen.kt`'s `legalHoldIndicator` already established for
 * its own currently-dead branch.
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
 * D2's permanent honesty caption, shown directly under the compose form's "Als Entwurf speichern"
 * button -- always visible, not conditional on having just sent a message. `sendMailingMessage`'s
 * "send" is a stub: it writes one [network.lapis.cloud.shared.domain.MailingDeliveryLogDto] row per
 * active subscriber with [network.lapis.cloud.shared.domain.DeliveryStatus.SENT] unconditionally
 * (`MailingService.kt`'s `runCatching { DeliveryStatus.SENT }` can never actually fail, per its own
 * inline comment) -- no real SMTP/delivery provider is wired. Same honesty posture as
 * `DsgvoRightsScreen.kt`'s `ERASURE_SELF_STATUS_VISIBILITY_CAPTION` and this project's README's own
 * documented `NoOpPasswordResetMailer` precedent.
 */
const val MAILING_SEND_STUB_CAPTION =
    "Der Versand ist in dieser Version ein interner Protokolleintrag -- es wird noch keine echte " +
        "E-Mail über einen externen Versanddienst verschickt. Jede aktive Abonnentin und jeder aktive " +
        "Abonnent erhält einen Eintrag mit Status \"Gesendet\" im Systemprotokoll, keine tatsächliche " +
        "Zustellung."
