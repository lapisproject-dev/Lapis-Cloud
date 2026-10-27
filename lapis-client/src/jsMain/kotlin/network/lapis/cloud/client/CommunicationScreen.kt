package network.lapis.cloud.client

import io.kvision.form.check.CheckBox
import io.kvision.form.select.select
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.table.row
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.MailingDeliveryMode
import network.lapis.cloud.shared.domain.MailingHtmlPolicy
import network.lapis.cloud.shared.domain.MailingListDto
import network.lapis.cloud.shared.domain.MailingListKind
import network.lapis.cloud.shared.domain.MailingListSubscriptionDto
import network.lapis.cloud.shared.domain.MailingMessageStatsDto
import network.lapis.cloud.shared.domain.MailingMessageStatus
import network.lapis.cloud.shared.rpc.IMailingService
import network.lapis.cloud.shared.rpc.IMemberService

// V1.9.231 -- the former single "Kommunikation" screen is split: the member's conversations live in `MessagesScreen.kt`, the mailing-list
// subscriptions and the board's authoring tier in `MailingListsScreen.kt`. This file keeps the building blocks of the mailing-list tier
// (tracking switches, the authoring section, the detail panel, the statistics panel) and the pure helpers (`CommunicationScreenTest`).

/** The two opt-in switches under one subscribed list -- see [renderTrackingConsentSwitches]. */
internal class TrackingConsentSwitches(
    val form: LapisForm,
    val openField: LapisField,
    val clickField: LapisField,
) {
    /** Disables both switches while a request is running (no second change can overtake the first). */
    fun setBusy(busy: Boolean) {
        (openField.control as? CheckBox)?.disabled = busy
        (clickField.control as? CheckBox)?.disabled = busy
    }
}

/**
 * Welle V1.9.15 "SuperMailer" Teil B/C -- the member's own, voluntary opt-in to counting: one switch for
 * "open" (an invisible image in the mail), one for "click" (links run through this server first). Both
 * start OFF, each is independent, and changing either reports BOTH values to [onChange] -- the server
 * applies them in one call. Withdrawing also erases what was already counted for that list.
 *
 * Built with a [lapisForm] and [LapisForm.checkField] (R24B: no bare labelled `checkBox`). The switch look
 * is Bootstrap's `form-switch` on the checkbox widget.
 */
internal fun renderTrackingConsentSwitches(
    host: SimplePanel,
    list: MailingListDto,
    onChange: (open: Boolean, click: Boolean) -> Unit,
): TrackingConsentSwitches {
    val box = host.vPanel(spacing = 2) { addCssClasses("ps-3 pb-2") }
    box.div(tr("Auswertung der Nachrichten (freiwillig)")) { addCssClasses("text-muted small fw-bold") }
    val form = box.lapisForm()
    val openField =
        form.checkField(
            label = tr("Öffnungen zählen"),
            value = list.currentMemberOpenTrackingConsentedAt != null,
            hint =
                tr(
                    "Ein unsichtbares Bild in der E-Mail meldet, dass sie geöffnet wurde. " +
                        "Nur mit Ihrer Einwilligung, jederzeit widerrufbar.",
                ),
            init = { it.addCssClass("form-switch") },
        )
    val clickField =
        form.checkField(
            label = tr("Klicks zählen"),
            value = list.currentMemberClickTrackingConsentedAt != null,
            hint =
                tr(
                    "Links in der E-Mail laufen zuerst über unseren Server, der den Klick zählt. " +
                        "Nur mit Ihrer Einwilligung, jederzeit widerrufbar.",
                ),
            init = { it.addCssClass("form-switch") },
        )
    form.finish()
    // KVision's `subscribe` reports the CURRENT value immediately on subscription (and again on every change): only a real
    // change against the last known state may reach [onChange], otherwise merely rendering the switches would call the server.
    var known = (openField.value == "true") to (clickField.value == "true")
    val emit = {
        val now = (openField.value == "true") to (clickField.value == "true")
        if (now != known) {
            known = now
            onChange(now.first, now.second)
        }
    }
    openField.subscribe { emit() }
    clickField.subscribe { emit() }
    return TrackingConsentSwitches(form, openField, clickField)
}

// ================================================================================================
// D1: admin-authoring tier -- BOARD/ADMIN only: create lists, force-subscribe members, compose and
// send messages. Never touched by a plain MEMBER -- the whole section is gated at the call site (the "Verwalten" tab of
// `renderMailingListsScreen`, which is not even built for a plain member), not per-widget here. The server stays the authority.
// ================================================================================================

internal fun renderMailingListAdminSection(
    root: SimplePanel,
    refreshSelfService: () -> Unit,
) {
    val titleSlot = root.sectionTitleRow(tr("Mailinglisten verwalten"))
    root.div(
        tr("Mailinglisten anlegen, Mitglieder gezielt eintragen und Nachrichten an eine Liste verschicken."),
    ) { addCssClasses("text-muted small") }
    val createHost = root.formSection()

    // `refreshListSelector` breaks the same forward-reference cycle `LedgerScreen.kt`'s
    // `refreshJournalFn` does: the create-form's success callback needs to refresh the selector
    // built below it, but the selector doesn't exist yet at the point the create form is rendered.
    var refreshListSelector: ((selectId: String?) -> Unit)? = null

    collapsibleCreateForm<Unit>(
        actionSlot = titleSlot,
        formHost = createHost,
        buttonLabel = tr("Neue Mailingliste"),
        formId = "mailing-list-create",
    ) { _, close ->
        renderCreateMailingListForm(refreshSelfService, close) { newListId -> refreshListSelector?.invoke(newListId) }
    }

    refreshListSelector = renderManageListSelector(root, refreshSelfService)
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
    val row = root.lapisToolbar()
    val listSelect = row.select(options = emptyList(), label = tr("Mailingliste"))
    val manageButton = row.actionButton(ActionIcon.VIEW, tr("Öffnen"), ButtonStyle.OUTLINESECONDARY)
    val detailPanel = root.vPanel(spacing = 10)

    var lists: List<MailingListDto> = emptyList()

    fun refresh(selectId: String?) {
        AppScope.launch {
            lists = guarded { rpcService<IMailingService>().listMailingLists() } ?: emptyList()
            // Security audit follow-up (untrusted-text sanitization gaps): the option label carries the
            // member-/admin-editable list name -- sanitize via untrustedOptions like the memberSelect.options
            // assignment below in this same file.
            listSelect.options =
                untrustedOptions(
                    lists.map {
                        it.id to
                            if (it.kind == MailingListKind.DYNAMIC) {
                                gettext("%1 (automatisch)", it.name)
                            } else {
                                gettext("%1 (%2 Abonnenten)", it.name, it.subscriberCount)
                            }
                    },
                )
            listSelect.value = selectId?.takeIf { id -> lists.any { it.id == id } } ?: lists.firstOrNull()?.id
        }
    }
    refresh(null)

    manageButton.onClick {
        val selected = lists.find { it.id == listSelect.value } ?: return@onClick
        // V1.9.234: after a deletion the detail is emptied and the selector reloaded (the deleted list is gone from the server's answer).
        renderMailingListDetail(detailPanel, selected, refreshSelfService, onDeleted = {
            detailPanel.removeAll()
            refresh(null)
        })
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
    onDeleted: () -> Unit = {},
) {
    panel.removeAll()
    val detail = panel.vPanel(spacing = 10) { addCssClasses("border rounded p-3") }
    // Security audit W6b follow-up round 3 (major finding A): mailing list name/description are admin-editable
    // free text rendered as raw widget content -- sanitize before KVision can resolve a forged marker on render.
    val header = detail.hPanel(spacing = 8) { addCssClasses("justify-content-between align-items-start") }
    header.div(sanitizeUntrustedI18nText(list.name)) { addCssClass("fw-bold") }
    // V1.9.234: only a MANUAL list can be deleted; an automatic one gets the reason instead of the button.
    if (list.kind == MailingListKind.MANUAL) {
        header.tableActionButton(ActionIcon.DELETE, tr("Mailingliste löschen"), ButtonStyle.OUTLINEDANGER).onClick {
            confirmDeleteMailingList(list, refreshSelfService, onDeleted)
        }
    }
    list.description?.takeIf { it.isNotBlank() }?.let { description ->
        detail.div(sanitizeUntrustedI18nText(description)) { addCssClasses("text-muted small") }
    }

    val dynamic = list.kind == MailingListKind.DYNAMIC
    if (dynamic) {
        detail.div(tr("Automatische Mailinglisten können nicht gelöscht werden.")) { addCssClasses("text-muted small") }
        // V1.9.111: nobody is subscribed by hand -- the rule decides at send time. After a saved rule both list panels are refreshed
        // and this detail is built again from the server's current state.
        renderDynamicListAdminBlock(detail, list) {
            refreshSelfService()
            AppScope.launch {
                val updated = guarded { rpcService<IMailingService>().listMailingLists() }?.firstOrNull { it.id == list.id }
                if (updated != null) renderMailingListDetail(panel, updated, refreshSelfService, onDeleted)
            }
        }
    }

    // ---- Abonnenten
    if (!dynamic) renderManualSubscriberSection(detail, list, refreshSelfService)
    renderMailingMessagesSection(detail, list)
}

/**
 * V1.9.234 -- the confirmation before a MANUAL list is deleted, then the RPC. The server decides everything (an automatic list, a send in
 * progress, a missing right): the client checks nothing beforehand and shows the typed refusal text of [guarded].
 */
internal fun confirmDeleteMailingList(
    list: MailingListDto,
    refreshSelfService: () -> Unit,
    onDeleted: () -> Unit,
) {
    confirmDialog(
        title = tr("Mailingliste löschen?"),
        message = gettext("Die Mailingliste „%1“ wird gelöscht.", sanitizeUntrustedI18nText(list.name)),
        extraLines =
            listOf(
                ngettext("%1 Abonnent wird ausgetragen.", "%1 Abonnenten werden ausgetragen.", list.subscriberCount, list.subscriberCount),
                tr("Entwürfe dieser Liste werden mit ihren Anhängen gelöscht."),
                tr("Bereits versendete Nachrichten bleiben als Nachweis erhalten."),
            ),
        dangerNote = tr("Das kann nicht rückgängig gemacht werden."),
        confirmLabel = tr("Löschen"),
        confirmIcon = ActionIcon.DELETE,
        confirmStyle = ButtonStyle.DANGER,
        focusCancel = true,
    ) {
        AppScope.launch {
            guarded { rpcService<IMailingService>().deleteMailingList(list.id) } ?: return@launch
            notifySuccess(tr("Mailingliste gelöscht."))
            onDeleted()
            refreshSelfService()
        }
    }
}

/** The manual part of the detail (V1.9.111: split out of [renderMailingListDetail]): subscribers and "Mitglied hinzufügen". */
private fun renderManualSubscriberSection(
    detail: SimplePanel,
    list: MailingListDto,
    refreshSelfService: () -> Unit,
) {
    detail.div(tr("Abonnenten")) { addCssClasses("fw-bold mt-2") }
    // Name filter over the fully loaded subscriber list (`listSubscribers` has no paging); hidden while the list is empty.
    val subscriberFilter = detail.listFilterField()
    subscriberFilter.setVisible(false)
    val subscribersPanel = detail.vPanel(spacing = 4)
    var loadedSubscribers: List<MailingListSubscriptionDto> = emptyList()

    fun renderSubscribers() {
        subscribersPanel.removeAll()
        if (loadedSubscribers.isEmpty()) {
            subscriberFilter.setVisible(false)
            subscribersPanel.p(tr("Noch keine Abonnenten."))
            return
        }
        subscriberFilter.setVisible(true)
        val visible = subscriberFilter.apply(loadedSubscribers, nameOf = { it.memberDisplayName })
        if (visible.isEmpty()) {
            subscribersPanel.p(gettext("Kein Eintrag passt zu \"%1\".", subscriberFilter.term)) { addCssClasses("text-muted") }
            return
        }
        visible.forEach { subscriber -> renderSubscriberRow(subscribersPanel, subscriber) }
    }
    subscriberFilter.subscribe { renderSubscribers() }

    fun refreshSubscribers() {
        subscribersPanel.removeAll()
        AppScope.launch {
            val subscribers = guarded { rpcService<IMailingService>().listSubscribers(list.id) } ?: return@launch
            loadedSubscribers = subscribers
            renderSubscribers()
        }
    }
    refreshSubscribers()

    // ---- Mitglied hinzufügen ---------------------------------------------------------------------
    detail.div(tr("Mitglied hinzufügen")) { addCssClasses("fw-bold mt-2") }
    val addForm = detail.lapisForm()
    val addRow = addForm.panel.hPanel(spacing = 8) { addCssClasses("align-items-end") }
    val memberField =
        addForm.searchableSelectField(
            label = tr("Mitglied"),
            options = listOf("" to gettext("— bitte wählen —")),
            value = "",
            required = true,
            host = addRow,
            requiredMessage = gettext("Bitte ein Mitglied auswählen."),
        )
    val memberSelect = memberField.control as SearchableSelect
    val addButton = addRow.actionButton(ActionIcon.ADD, tr("Hinzufügen"), style = ButtonStyle.OUTLINEPRIMARY)
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
}

/** The messages part of the detail (compose, draft list, send) -- the same for manual and automatic lists. */
private fun renderMailingMessagesSection(
    detail: SimplePanel,
    list: MailingListDto,
) {
    // ---- Nachrichten -------------------------------------------------------------------------
    detail.div(tr("Nachrichten")) { addCssClasses("fw-bold mt-2") }
    val composePanel = detail.vPanel(spacing = 6) { addCssClasses("border rounded p-3") }
    val composeForm = composePanel.lapisForm()
    val subjectField = composeForm.textField(label = tr("Betreff"), required = true)
    // Welle V1.9.15 Teil A: the plain textarea became the WYSIWYG editor (MailingHtmlEditor). The text is a
    // required part of the form, but it is not a LapisField -- a cross-field rule carries the "enter a text"
    // check into the same collective message area and focus handling every other field uses.
    val editor =
        MailingHtmlEditor(
            host = composeForm.panel,
            labelText = tr("Text"),
            subjectProvider = { subjectField.value },
            onEdited = { composeForm.onFieldStateChanged() },
        )
    composeForm.crossFieldRule(focusOn = editor.editable) {
        if (editor.isBlank()) FieldCheck.Invalid(gettext("Bitte einen Text eingeben.")) else FieldCheck.Ok
    }
    val draftButton = newActionButton(ActionIcon.SAVE, tr("Als Entwurf speichern"), ButtonStyle.OUTLINEPRIMARY)
    composeForm.buttons(primary = draftButton)
    // V1.9.232: attachments belong to the saved draft (the message needs an id first).
    composePanel.div(tr("Anhänge fügen Sie nach dem Speichern beim Entwurf hinzu.")) { addCssClasses("text-muted small") }
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
            messages.forEach { message ->
                renderMailingMessageRow(
                    messagesPanel,
                    message,
                    list.name,
                    ::refreshMessages,
                    dynamic = list.kind == MailingListKind.DYNAMIC,
                    mandatory = list.mandatory,
                )
            }
        }
    }
    refreshMessages()

    draftButton.onClick {
        composeForm.submit(draftButton) {
            val subject = subjectField.value.trim()
            val result = guarded { rpcService<IMailingService>().createDraftMessageHtml(list.id, subject, editor.html()) }
            if (result != null) {
                notifySuccess(gettext("Entwurf \"%1\" wurde gespeichert.", subject))
                subjectField.reset()
                editor.clear()
                refreshMessages()
            }
        }
    }
}

/**
 * Welle V1.9.15 -- renders [stats] into [host]. Aggregates only: the server never sends an id, a name or a
 * personal timestamp, and numbers below [MailingHtmlPolicy.MIN_CONSENTS_FOR_STATS] arrive as `null`, which
 * is shown as a plain explanation rather than a zero (a zero would claim "nobody clicked"). **No
 * percentages**: the open count is an estimate (image blockers hide opens, some mail programs load images
 * automatically), and a percentage of an estimate invites a precision nobody has.
 */
internal fun renderMailingStatsPanel(
    host: SimplePanel,
    stats: MailingMessageStatsDto,
) {
    val box = host.vPanel(spacing = 4) { addCssClasses("border rounded p-2") }
    box.div(gettext("Zugestellt: %1", stats.delivered))
    if (stats.retentionExpired) {
        box.div(tr("Auswertung nach Aufbewahrungsfrist gelöscht.")) { addCssClasses("text-muted small") }
        return
    }
    stats.openedAtLeastOnce?.let { opened ->
        box.div(gettext("Geöffnet (Schätzwert): %1 von %2 mit Einwilligung", opened, stats.openCohort))
    }
    if (!stats.clickSuppressed && stats.links.isNotEmpty()) {
        val table =
            box.standardTable(
                listOf(TableHeader(tr("Link")), TableHeader(tr("Empfänger"), numeric = true), TableHeader(tr("Klicks"), numeric = true)),
            )
        stats.links.forEach { link ->
            table.row {
                // The target URL is message content typed by a board member: never as raw widget content.
                textCell(link.targetUrl) { addCssClass("text-break") }
                numCell((link.uniqueRecipients ?: 0).toString())
                numCell((link.totalClicks ?: 0).toString())
            }
        }
    }
    if (stats.suppressed) {
        box.div(gettext("Zu wenige Einwilligungen für eine Auswertung (mindestens %1).", MailingHtmlPolicy.MIN_CONSENTS_FOR_STATS)) {
            addCssClasses("text-muted small")
        }
    }
    box.div(tr("Öffnungen sind ungenau: Bildblocker unterdrücken sie, manche Mail-Programme laden Bilder automatisch.")) {
        addCssClasses("text-muted small")
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
