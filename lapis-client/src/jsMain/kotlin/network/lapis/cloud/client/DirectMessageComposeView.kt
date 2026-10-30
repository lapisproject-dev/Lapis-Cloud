package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.H2
import io.kvision.html.div
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.simplePanel
import kotlinx.browser.window
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.DirectMessageDto
import network.lapis.cloud.shared.domain.DirectMessageGroupDto
import network.lapis.cloud.shared.domain.DirectMessageGroupSendResultDto
import network.lapis.cloud.shared.domain.DirectMessageRecipientDto
import network.lapis.cloud.shared.domain.DirectMessageRules
import network.lapis.cloud.shared.rpc.IDirectMessageService

/** The RPC surface of the compose view -- an interface so DOM tests can drive it without a server. Exceptions propagate. */
internal interface DirectMessageComposeRpc {
    suspend fun searchMessageRecipients(query: String): List<DirectMessageRecipientDto>

    suspend fun listMessageGroups(): List<DirectMessageGroupDto>

    suspend fun sendGroupMessage(
        committeeId: String,
        body: String,
    ): DirectMessageGroupSendResultDto

    suspend fun sendWithAttachments(
        recipientId: String,
        body: String,
        attachmentIds: List<String>,
    ): DirectMessageDto
}

internal fun liveDirectMessageComposeRpc(): DirectMessageComposeRpc =
    object : DirectMessageComposeRpc {
        override suspend fun searchMessageRecipients(query: String) = rpcService<IDirectMessageService>().searchMessageRecipients(query)

        override suspend fun listMessageGroups() = rpcService<IDirectMessageService>().listMessageGroups()

        override suspend fun sendGroupMessage(
            committeeId: String,
            body: String,
        ) = rpcService<IDirectMessageService>().sendGroupMessage(committeeId, body)

        override suspend fun sendWithAttachments(
            recipientId: String,
            body: String,
            attachmentIds: List<String>,
        ) = rpcService<IDirectMessageService>().sendDirectMessageWithAttachments(recipientId, body, attachmentIds)
    }

/** Pause after the last keystroke before the search runs. A timeout, never a polling timer. */
internal const val RECIPIENT_SEARCH_DEBOUNCE_MS = 300

/** Element ids of the two segment buttons (tests and `aria-controls`). */
internal const val COMPOSE_SEGMENT_PERSON_ID = "lapis-dm-segment-person"
internal const val COMPOSE_SEGMENT_GROUP_ID = "lapis-dm-segment-group"

/** Up to two initials of a (sanitized) display name -- decoration only, always `aria-hidden`. Pure. */
internal fun nameInitials(name: String): String {
    val letters =
        name
            .trim()
            .split(' ')
            .filter { it.isNotEmpty() }
            .take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
    return letters.joinToString("").ifEmpty { "?" }
}

/**
 * V1.9.237 "Neue Nachricht" -- replaces the list area of the Nachrichten page (no id ever goes into the URL). Two modes behind a segment control
 * ("An eine Person" / "An eine Gruppe"):
 * - **Person**: a name search (at least [DirectMessageRules.RECIPIENT_QUERY_MIN] characters, 300 ms debounce; shorter input makes NO call), the hits as
 *   buttons, a chip for the chosen recipient and then the same form as a reply, with attachments. Changing the recipient discards the drafts
 *   (pending uploads are bound to the recipient).
 * - **Group**: the committees the caller may write to as a radio list, text only (attachments are not possible for groups).
 * The search term, the typed text and file names are never logged, stored or put in a URL or toast. [onSentToPerson] gets the partner id (the list
 * opens that conversation from memory), [onSentToGroup] the committee name.
 */
internal fun SimplePanel.directMessageComposeView(
    rpc: DirectMessageComposeRpc = liveDirectMessageComposeRpc(),
    attachmentHttp: DirectMessageAttachmentHttp = DirectMessageHttp,
    onSentToPerson: (String) -> Unit,
    onSentToGroup: (String) -> Unit,
    onBack: () -> Unit,
) {
    backButton(tr("Zur Liste"), onBack)
    add(H2(content = tr("Neue Nachricht"), className = "h5"))
    val segment = div(className = "btn-group lapis-dm-segment") { setAttribute("role", "radiogroup") }
    segment.setAttribute("aria-label", gettext("Empfänger"))
    val personTab = Button(tr("An eine Person"), style = ButtonStyle.OUTLINESECONDARY)
    val groupTab = Button(tr("An eine Gruppe"), style = ButtonStyle.OUTLINESECONDARY)
    personTab.setAttribute("id", COMPOSE_SEGMENT_PERSON_ID)
    groupTab.setAttribute("id", COMPOSE_SEGMENT_GROUP_ID)
    listOf(personTab, groupTab).forEach {
        it.setAttribute("role", "radio")
        it.setAttribute("type", "button")
        segment.add(it)
    }
    val personPanel = simplePanel()
    val groupPanel = simplePanel()
    var groupBuilt = false

    fun show(person: Boolean) {
        personTab.setAttribute("aria-checked", person.toString())
        groupTab.setAttribute("aria-checked", (!person).toString())
        personPanel.visible = person
        groupPanel.visible = !person
        if (!person && !groupBuilt) {
            groupBuilt = true
            groupPanel.groupComposeSection(rpc, onSentToGroup)
        }
    }
    personPanel.personComposeSection(rpc, attachmentHttp, onSentToPerson)
    personTab.onClick { show(person = true) }
    groupTab.onClick { show(person = false) }
    show(person = true)
}

private fun SimplePanel.personComposeSection(
    rpc: DirectMessageComposeRpc,
    attachmentHttp: DirectMessageAttachmentHttp,
    onSent: (String) -> Unit,
) {
    val searchPanel = simplePanel()
    val chosenPanel = simplePanel()
    val form = searchPanel.lapisForm()
    val field =
        form.textField(
            label = tr("Name des Mitglieds"),
            hint = tr("Mindestens 3 Zeichen"),
        )
    val results = searchPanel.simplePanel()
    val status = searchPanel.div(className = "visually-hidden") { setAttribute("role", "status") }
    var timer: Int? = null
    var serial = 0

    fun renderHits(hits: List<DirectMessageRecipientDto>) {
        results.removeAll()
        hits.forEach { hit ->
            val name = sanitizeUntrustedI18nText(hit.displayName)
            val button = Button("", style = ButtonStyle.OUTLINESECONDARY, className = "lapis-dm-hit mb-1")
            button.setAttribute("type", "button")
            button.span(nameInitials(name), className = "lapis-dm-avatar") { setAttribute("aria-hidden", "true") }
            button.span(name)
            if (hit.hasConversation) button.span(tr("bereits im Gespräch"), className = "text-muted small ms-auto")
            button.onClick { chooseRecipient(rpc, attachmentHttp, hit, searchPanel, chosenPanel, onSent) }
            results.add(button)
        }
        status.content = if (hits.isEmpty()) tr("Keine Treffer.") else ""
        if (hits.isEmpty()) results.div(tr("Keine Treffer."), className = "text-muted")
    }

    fun runSearch(term: String) {
        val mine = ++serial
        AppScope.launch {
            val hits = guarded { rpc.searchMessageRecipients(term) } ?: return@launch
            if (mine == serial) renderHits(hits)
        }
    }
    field.subscribe { raw ->
        timer?.let { window.clearTimeout(it) }
        serial++
        val term = raw.trim()
        if (term.length < DirectMessageRules.RECIPIENT_QUERY_MIN) {
            results.removeAll()
            timer = null
        } else {
            timer = window.setTimeout({ runSearch(term) }, RECIPIENT_SEARCH_DEBOUNCE_MS)
        }
    }
}

private fun chooseRecipient(
    rpc: DirectMessageComposeRpc,
    attachmentHttp: DirectMessageAttachmentHttp,
    hit: DirectMessageRecipientDto,
    searchPanel: SimplePanel,
    chosenPanel: SimplePanel,
    onSent: (String) -> Unit,
) {
    searchPanel.visible = false
    chosenPanel.removeAll()
    val attachments = DmComposerAttachments(hit.memberId, attachmentHttp)
    val name = sanitizeUntrustedI18nText(hit.displayName)
    val chip = chosenPanel.div(className = "lapis-dm-chip mb-2")
    chip.span(nameInitials(name), className = "lapis-dm-avatar") { setAttribute("aria-hidden", "true") }
    chip.span(name)
    val remove = newActionButton(ActionIcon.CANCEL, tr("Empfänger entfernen"), ButtonStyle.LIGHT, iconOnly = true)
    chip.add(remove)
    val formHost = chosenPanel.simplePanel()
    formHost.directMessageReplyForm(
        recipientId = hit.memberId,
        onSent = { onSent(hit.memberId) },
        attachments = attachments,
        send = rpc::sendWithAttachments,
        texts = DirectMessageFormTexts.NEW_MESSAGE,
    )
    remove.onClick {
        // Pending uploads are bound to the recipient: they are discarded with the choice, or the next send would be refused.
        attachments.drafts.forEach { attachments.remove(it) }
        chosenPanel.removeAll()
        searchPanel.visible = true
    }
}

private fun SimplePanel.groupComposeSection(
    rpc: DirectMessageComposeRpc,
    onSent: (String) -> Unit,
) {
    var selected: DirectMessageGroupDto? = null
    dataSection<List<DirectMessageGroupDto>>(
        emptyText = tr("Sie gehören keiner Gruppe an, an die Sie schreiben können."),
        isEmpty = { it.isEmpty() },
        load = { guarded { rpc.listMessageGroups() } },
        render = { panel, groups ->
            val radios = mutableMapOf<String, Button>()
            val list = panel.div(className = "d-grid gap-1 mb-2") { setAttribute("role", "radiogroup") }
            list.setAttribute("aria-label", gettext("Gruppe"))
            val summary = panel.div(className = "text-muted mb-2") { setAttribute("role", "status") }
            val form = panel.lapisForm()
            val body = form.textAreaField(label = tr("Nachricht"), rows = 3, required = true)
            form.panel.div(tr("Anhänge sind bei Gruppennachrichten nicht möglich."), className = "text-muted small")
            val send = newActionButton(ActionIcon.SEND, tr("An Gruppe senden"), ButtonStyle.OUTLINEPRIMARY)
            send.disabled = true
            form.buttons(primary = send)
            groups.forEach { group ->
                val name = sanitizeUntrustedI18nText(group.name)
                val radio = Button("", style = ButtonStyle.OUTLINESECONDARY, className = "lapis-dm-hit")
                radio.setAttribute("role", "radio")
                radio.setAttribute("type", "button")
                radio.setAttribute("aria-checked", "false")
                radio.span(nameInitials(name), className = "lapis-dm-avatar") { setAttribute("aria-hidden", "true") }
                radio.span(name)
                // V1.9.242: the number is the recipients (the sender is not counted), not the size of the committee
                radio.span(gettext("Empfänger: %1", group.memberCount), className = "text-muted small ms-auto") {
                    title = gettext("Ohne Sie selbst, ohne ausgeschiedene und inaktive Mitglieder")
                }
                radio.onClick {
                    selected = group
                    radios.forEach { (id, button) -> button.setAttribute("aria-checked", (id == group.committeeId).toString()) }
                    summary.content =
                        gettext("Ihre Nachricht geht einzeln an alle Mitglieder der Gruppe „%1“ (Empfänger: %2).", name, group.memberCount)
                    send.disabled = false
                }
                radios[group.committeeId] = radio
                list.add(radio)
            }
            send.onClick {
                val group = selected ?: return@onClick
                form.submit(send) {
                    val result = guarded { rpc.sendGroupMessage(group.committeeId, body.value.trim()) }
                    if (result != null) {
                        notifySuccess(gettext("An die Gruppe „%1“ gesendet.", sanitizeUntrustedI18nText(result.committeeName)))
                        onSent(result.committeeName)
                    }
                }
            }
        },
    ).reload()
}
