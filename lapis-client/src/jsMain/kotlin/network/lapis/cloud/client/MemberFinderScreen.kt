package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.core.onClick
import io.kvision.form.check.CheckBox
import io.kvision.form.check.RadioGroup
import io.kvision.form.text.TextArea
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.TAG
import io.kvision.html.Tag
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.h3
import io.kvision.html.link
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.modal.Modal
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.MemberFinderConnectionDto
import network.lapis.cloud.shared.domain.MemberFinderConsentTextDto
import network.lapis.cloud.shared.domain.MemberFinderIncomingRequestDto
import network.lapis.cloud.shared.domain.MemberFinderInterest
import network.lapis.cloud.shared.domain.MemberFinderOutgoingRequestDto
import network.lapis.cloud.shared.domain.MemberFinderOutgoingState
import network.lapis.cloud.shared.domain.MemberFinderPurpose
import network.lapis.cloud.shared.domain.MemberFinderRules
import network.lapis.cloud.shared.domain.MemberFinderStateDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.IMemberFinderService
import network.lapis.cloud.shared.rpc.MemberFinderConsentOutdatedException
import network.lapis.cloud.shared.rpc.MemberFinderConsentRequiredException
import network.lapis.cloud.shared.rpc.MemberFinderNotEligibleException
import network.lapis.cloud.shared.rpc.MemberFinderRateLimitedException
import network.lapis.cloud.shared.rpc.MemberFinderUnavailableException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.w3c.dom.HTMLElement

/*
 * V1.9.145 -- the member finder of an ACTIVE member (see docs/features/member-finder.adoc).
 *
 * Two modes. Without an effective consent (mode A) the member reads the consent text, chooses an interest, confirms being of age and takes part.
 * With one (mode B) the page shows, in the order of urgency: the own participation, incoming requests, connections, the form for a new request,
 * the own requests and the blocks. A list that is empty is not drawn at all, not even its heading.
 *
 * Privacy rules of this client (a tripwire scans this file, see ClientMemberFinderPrivacyTripwireTest):
 * - No identity before both sides agreed: an incoming request shows the purpose, dates and the note, never the sender; a sent request answers
 *   the same way whatever the server found. The name of the other side is drawn in ONE place, the connection card.
 * - Note and name are somebody else's text: they only ever go through the untrusted* helpers, never through tr()/gettext(), never into an
 *   aria-label, a toast, the live region or the title. Only the random ids of requests, connections and blocks may be in the DOM.
 * - Nothing is written to the console or the storage of the browser.
 * - Reads cost the hourly budget of the member (240): after an action only the affected list is read again, no polling.
 */

private var finderRadioSerial = 0

/** Entry of the route: the status of the session decides before a single call is made (the server refuses everybody but ACTIVE). */
fun renderMemberFinderScreen(container: SimplePanel) {
    val root =
        container.pageShell(PageWidth.WIDE, spacing = 14)
    renderMemberFinderContent(root, AppState.session?.status ?: MemberStatus.GUEST)
}

/** The testable entry: draws the page into [root] for a member of [status]. */
internal fun renderMemberFinderContent(
    root: SimplePanel,
    status: MemberStatus,
) {
    // The intro describes the opt-in finder; a person who is not a member only gets the "members only" sentence below it.
    root.pageHeader(
        tr("Mitglieder-Finder"),
        intro = if (status in MemberStatusSets.ORGANIZATION_MEMBER) PageIntros.forRoute(Routes.MEMBER_FINDER) else null,
    )
    if (status !in MemberStatusSets.ORGANIZATION_MEMBER) {
        root.p(tr("Der Mitglieder-Finder steht nur Mitgliedern zur Verfügung."), className = "text-muted")
        return
    }
    MemberFinderPage(root).start()
}

/** A read of the finder: the fixed sentence for a throttled read, `guarded` (toast) for everything else; `null` = failed. */
private suspend fun <T> finderRead(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: MemberFinderRateLimitedException) {
        notifyError(tr("Zu viele Aufrufe in kurzer Zeit. Bitte versuchen Sie es später erneut."))
        null
    } catch (e: Throwable) {
        guarded { throw e }
    }

/**
 * An action of the finder: a typed refusal is shown as its fixed sentence through [show]; a missing request ([onNotFound]) is handled quietly where
 * the caller asked for it; every other failure goes through `guarded`. [onFailure] sees the exception first (the page uses it to notice that it is out of date).
 */
private suspend fun <T> finderAct(
    show: (String) -> Unit,
    onFailure: (Throwable) -> Unit = {},
    onNotFound: (() -> Unit)? = null,
    block: suspend () -> T,
): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        onFailure(e)
        val text = memberFinderErrorText(e)
        if (text != null) {
            show(text)
            null
        } else if (e is NotFoundException && onNotFound != null) {
            onNotFound()
            null
        } else {
            guarded { throw e }
        }
    }

/** Lets a caller wait until a [dataSection] has settled a load (its `onSettled` fires), so that a message can follow the new list. */
private class SettleSignal {
    private var waiting: CompletableDeferred<Unit>? = null

    fun arm(): CompletableDeferred<Unit> = waiting ?: CompletableDeferred<Unit>().also { waiting = it }

    fun settled() {
        waiting?.complete(Unit)
        waiting = null
    }
}

/** A [dataSection] plus the means to wait for its next load. */
private class FinderList(
    private val section: DataSection,
    private val signal: SettleSignal,
) {
    fun reload() = section.reload()

    suspend fun reloadAndWait() {
        val done = signal.arm()
        section.reload()
        done.await()
    }
}

private fun <T> Container.finderList(
    load: suspend () -> T?,
    render: (SimplePanel, T) -> Unit,
): FinderList {
    val signal = SettleSignal()
    val section =
        dataSection<T>(
            isEmpty = { false },
            onSettled = { signal.settled() },
            load = load,
            render = render,
        )
    return FinderList(section, signal)
}

private class MemberFinderPage(
    private val root: SimplePanel,
) {
    /** Visually hidden, polite: the ONE live region of the page. Texts are fixed sentences, never a name or a note. */
    private val live: Div =
        root.div(className = "visually-hidden").also {
            it.setAttribute("role", "status")
            it.setAttribute("aria-live", "polite")
        }
    private val body: SimplePanel = root.vPanel(spacing = 14)
    private var content: SimplePanel? = null
    private lateinit var stateSection: DataSection

    fun announce(text: String) {
        live.content = text
    }

    fun start() {
        stateSection =
            body.dataSection<MemberFinderStateDto>(
                isEmpty = { false },
                load = { finderRead { rpcService<IMemberFinderService>().getState() } },
                render = { panel, state ->
                    content = panel
                    show(state)
                },
            )
        stateSection.reload()
    }

    fun reloadState() = stateSection.reload()

    /** Draws [state] (from `getState`, `optIn` or `optOut`) into the content area; the cached "offered" answer of the sidebar follows. */
    fun show(
        state: MemberFinderStateDto,
        joined: Boolean = false,
    ) {
        MemberFinderNav.update(state)
        val panel = content ?: return
        panel.removeAll()
        when {
            !state.offered ->
                panel.p(tr("Der Mitglieder-Finder ist auf dieser Instanz nicht eingeschaltet."), className = "text-muted")
            !state.eligible -> renderNotEligible(panel)
            state.optedIn -> ParticipationView(this, panel, state).render(focusStatus = joined)
            else -> renderJoin(panel, state)
        }
    }

    private fun renderIntro(panel: SimplePanel) {
        panel.p(
            tr(
                "Der Mitglieder-Finder hilft Ihnen, andere Mitglieder in Ihrer Region zu finden, um eine Ortsgruppe zu gründen oder sich zu treffen. Die Teilnahme ist freiwillig. Namen sehen Sie erst, wenn beide Seiten zugestimmt haben.",
            ),
        )
    }

    private fun renderNotEligible(panel: SimplePanel) {
        renderIntro(panel)
        val box = panel.div(className = "alert alert-info")
        box.setAttribute("role", "status")
        box.p(
            tr(
                "Für den Mitglieder-Finder benötigen wir Ihre Postanschrift mit deutscher Postleitzahl, und Sie müssen volljährig sein. Bitte prüfen Sie Ihre Angaben in Ihrem Profil.",
            ),
            className = "mb-2",
        )
        box.link(tr("Zum Profil"), url = "#${Routes.DSGVO_RIGHTS}")
        // Art. 7(3) GDPR: an earlier consent can be withdrawn even if the account is not eligible any more (the server treats a withdrawal
        // without an open consent as a no-op for the consent).
        val revoke = panel.actionButton(ActionIcon.LEAVE, tr("Frühere Einwilligung widerrufen"), style = ButtonStyle.OUTLINESECONDARY)
        revoke.onClick { confirmOptOut() }
    }

    /** The question before a withdrawal: Raskin's rule, the focus starts on "Abbrechen". */
    fun confirmOptOut() {
        confirmDialog(
            title = tr("Nicht mehr teilnehmen?"),
            message =
                tr(
                    "Ihre Einwilligung wird widerrufen. Ihre Anfragen, Verbindungen und Blockierungen werden gelöscht. Eine erneute Teilnahme ist frühestens 24 Stunden nach dem Widerruf möglich.",
                ),
            confirmLabel = tr("Nicht mehr teilnehmen"),
            focusCancel = true,
        ) {
            AppScope.launch {
                val state = finderAct(show = ::notifyError) { rpcService<IMemberFinderService>().optOut() }
                if (state != null) {
                    announce(tr("Ihre Teilnahme ist beendet."))
                    show(state)
                }
            }
        }
    }

    // ---- mode A ----

    private fun renderJoin(
        panel: SimplePanel,
        state: MemberFinderStateDto,
    ) {
        renderIntro(panel)
        val notice = panel.div(className = "text-warning")
        notice.setAttribute("role", "status")
        if (state.consentOutdated) {
            notice.content = tr("Der Text wurde aktualisiert, bitte neu lesen.")
        }
        var current: MemberFinderConsentTextDto? = null
        var frame: Div? = null
        val textSignal = SettleSignal()
        val textHost = panel.vPanel(spacing = 6)
        val textSection =
            textHost.dataSection<MemberFinderConsentTextDto>(
                isEmpty = { false },
                onSettled = { textSignal.settled() },
                load = { finderRead { rpcService<IMemberFinderService>().getConsentText() } },
                render = { host, dto ->
                    current = dto
                    host.h2(tr("Einwilligungstext")) { addCssClass("h5") }
                    frame =
                        host.agreementFrame(
                            text = dto.body,
                            ariaLabel = gettext("Einwilligungstext"),
                            format = dto.format,
                            renderedHtml = dto.renderedHtml,
                        )
                    host.p(tr("Bitte lesen Sie den vollständigen Text."), className = "text-muted small")
                },
            )
        textSection.reload()

        val serial = ++finderRadioSerial
        val fieldset = Tag(TAG.FIELDSET, className = "border-0 p-0 m-0")
        fieldset.add(Tag(TAG.LEGEND, tr("Woran haben Sie Interesse?"), className = "h6 mb-1"))
        val interest =
            RadioGroup(
                options =
                    listOf(
                        MemberFinderInterest.GROUP_FOUNDING.name to gettext("Ortsgruppe gründen"),
                        MemberFinderInterest.MEETUP.name to gettext("Stammtisch besuchen"),
                        MemberFinderInterest.BOTH.name to gettext("Beides"),
                    ),
                value = null,
                name = "member-finder-interest-$serial",
            )
        fieldset.add(interest)
        panel.add(fieldset)
        val adult = CheckBox(value = false, label = tr("Ich bestätige, dass ich volljährig bin."))
        panel.add(adult)

        val locked = state.nextOptInAllowedAt
        if (locked != null) {
            panel.p(gettext("Eine erneute Teilnahme ist ab %1 möglich.", formatSystemDateTime(locked)), className = "text-muted small")
        }
        val failure = panel.div(className = "text-danger")
        failure.setAttribute("role", "alert")
        val join = panel.actionButton(ActionIcon.ENTER, tr("Teilnehmen"), style = ButtonStyle.PRIMARY)

        fun chosen(): MemberFinderInterest? = interest.value?.let { value -> MemberFinderInterest.entries.firstOrNull { it.name == value } }

        fun canJoin(): Boolean = chosen() != null && adult.value && locked == null

        fun update() {
            join.disabled = !canJoin()
        }
        update()
        interest.subscribe { update() }
        adult.subscribe { update() }

        if (state.consentOutdated) {
            val revoke = panel.actionButton(ActionIcon.LEAVE, tr("Einwilligung widerrufen"), style = ButtonStyle.OUTLINESECONDARY)
            revoke.onClick { confirmOptOut() }
        }

        join.onClick {
            runGuardedAction(join, restoreDisabled = { !canJoin() }) {
                failure.content = ""
                val text = current ?: return@runGuardedAction
                val picked = chosen() ?: return@runGuardedAction
                val result =
                    finderAct(show = { failure.content = it }, onFailure = { handleStale(it) }) {
                        try {
                            rpcService<IMemberFinderService>().optIn(text.versionLabel, text.sha256, picked, true)
                        } catch (e: MemberFinderConsentOutdatedException) {
                            // The text changed under the member: read it again, keep the choices, say so above the frame.
                            notice.content = tr("Der Text wurde aktualisiert, bitte neu lesen.")
                            val settled = textSignal.arm()
                            textSection.reload()
                            settled.await()
                            frame?.focus()
                            null
                        }
                    }
                if (result != null) {
                    announce(tr("Sie nehmen jetzt teil."))
                    show(result, joined = true)
                }
            }
        }
    }

    // ---- shared by the mode B view ----

    /** Used when an action found that the page no longer matches the server (no consent, not eligible, switch off). */
    fun handleStale(e: Throwable): Boolean =
        when (e) {
            is MemberFinderConsentRequiredException,
            is MemberFinderConsentOutdatedException,
            is MemberFinderNotEligibleException,
            is MemberFinderUnavailableException,
            -> {
                reloadState()
                true
            }
            else -> false
        }
}

/** Mode B: the member takes part. */
private class ParticipationView(
    private val page: MemberFinderPage,
    private val panel: SimplePanel,
    private var state: MemberFinderStateDto,
) {
    private val service get() = rpcService<IMemberFinderService>()

    private lateinit var incoming: FinderList
    private lateinit var connections: FinderList
    private lateinit var outgoing: FinderList
    private lateinit var blocks: FinderList
    private lateinit var statusHost: SimplePanel
    private var pendingText: Div? = null
    private var sendButtonRefresh: () -> Unit = {}

    fun render(focusStatus: Boolean = false) {
        statusHost = panel.vPanel(spacing = 6)
        renderStatus()
        incoming = panel.finderList(load = { finderRead { service.listIncomingRequests() } }, render = ::renderIncoming)
        connections = panel.finderList(load = { finderRead { service.listConnections() } }, render = ::renderConnections)
        renderSendForm()
        outgoing = panel.finderList(load = { finderRead { service.listOutgoingRequests() } }, render = ::renderOutgoing)
        blocks = panel.finderList(load = { finderRead { service.listBlocks() } }, render = ::renderBlocks)
        incoming.reload()
        connections.reload()
        outgoing.reload()
        blocks.reload()
        if (focusStatus) {
            kotlinx.browser.window.setTimeout({ (document.getElementById(STATUS_TITLE_ID) as? HTMLElement)?.focus() }, 0)
        }
    }

    private fun notifyRefusal(text: String) = notifyError(text)

    /** An action of this page: a refusal that means the page is out of date redraws it from the server's state. */
    private suspend fun <T> act(
        show: (String) -> Unit = ::notifyRefusal,
        onNotFound: (() -> Unit)? = null,
        block: suspend () -> T,
    ): T? = finderAct(show = show, onFailure = { page.handleStale(it) }, onNotFound = onNotFound, block = block)

    /** Reads the state again and redraws the whole page if it no longer is mode B; otherwise only the counters follow. */
    private suspend fun refreshState() {
        val fresh = finderRead { service.getState() } ?: return
        MemberFinderNav.update(fresh)
        if (!fresh.offered || !fresh.eligible || !fresh.optedIn) {
            page.show(fresh)
            return
        }
        state = fresh
        renderStatus()
        sendButtonRefresh()
    }

    // ---- 1. status ----

    private fun renderStatus() {
        statusHost.removeAll()
        val box = statusHost.vPanel(spacing = 4) { addCssClasses("border rounded p-3") }
        box.h2(tr("Ihre Teilnahme"), className = "h5") {
            id = STATUS_TITLE_ID
            setAttribute("tabindex", "-1")
        }
        state.regionLabel?.let { box.div(gettext("Ihre Region: Postleitzahlbereich %1", it)) }
        box.div(memberFinderBandLabel(state.band))
        state.bandComputedOn?.let { box.div(gettext("Stand: %1", formatDate(it)), className = "text-muted small") }
        state.interest?.let { box.div(gettext("Ihr Interesse: %1", memberFinderInterestLabel(it))) }
        val leave = box.actionButton(ActionIcon.LEAVE, tr("Nicht mehr teilnehmen"), style = ButtonStyle.OUTLINEDANGER)
        leave.onClick { page.confirmOptOut() }
    }

    // ---- 2. incoming requests ----

    private fun renderIncoming(
        host: SimplePanel,
        items: List<MemberFinderIncomingRequestDto>,
    ) {
        if (items.isEmpty()) return
        host.h2(tr("Eingehende Anfragen")) { addCssClass("h5") }
        val list = host.tag(TAG.UL, className = "list-unstyled d-flex flex-column gap-2 mb-0")
        items.forEach { request ->
            val item = list.tag(TAG.LI, className = "border rounded p-3")
            item.setAttribute("data-request-id", request.requestId)
            item.h3(memberFinderRequestTemplate(request.purpose), className = "h6 mb-1")
            item.div(gettext("Erhalten am %1", formatDate(request.receivedOn)), className = "text-muted small")
            item.div(gettext("Läuft ab am %1", formatDate(request.expiresOn)), className = "text-muted small")
            request.note?.let { note ->
                item.div(tr("Notiz"), className = "text-muted small mt-1")
                item.untrustedDiv(note, className = "border-start ps-2 text-break")
            }
            val actions = item.div(className = "d-flex flex-wrap gap-2 mt-2")
            val received = formatDate(request.receivedOn)
            val accept = actions.actionButton(ActionIcon.APPROVE, tr("Annehmen"), style = ButtonStyle.PRIMARY)
            accept.setAttribute("aria-label", gettext("Anfrage vom %1 annehmen", received))
            val decline = actions.actionButton(ActionIcon.REJECT, tr("Ablehnen"), style = ButtonStyle.SECONDARY)
            decline.setAttribute("aria-label", gettext("Anfrage vom %1 ablehnen", received))
            val block = actions.actionButton(ActionIcon.REVOKE, tr("Ablehnen und blockieren"), style = ButtonStyle.OUTLINEDANGER)
            block.setAttribute("aria-label", gettext("Anfrage vom %1 ablehnen und blockieren", received))
            accept.onClick { respond(accept, request.requestId, accept = true, block = false) }
            decline.onClick { respond(decline, request.requestId, accept = false, block = false) }
            block.onClick {
                confirmDialog(
                    title = tr("Ablehnen und blockieren?"),
                    message =
                        tr(
                            "Diese Person kann Ihnen keine Anfragen mehr schicken. Wer es ist, erfahren Sie nicht. Sie können die Blockierung später aufheben.",
                        ),
                    confirmLabel = tr("Ablehnen und blockieren"),
                    focusCancel = true,
                ) { respond(block, request.requestId, accept = false, block = true) }
            }
        }
    }

    private fun respond(
        button: io.kvision.html.Button,
        requestId: String,
        accept: Boolean,
        block: Boolean,
    ) {
        runGuardedAction(button) {
            var gone = false
            val answer =
                act(
                    onNotFound = {
                        gone = true
                        notifyError(tr("Diese Anfrage ist nicht mehr offen."))
                    },
                ) { service.respondToRequest(requestId, accept, block) }
            if (answer == null) {
                // Expired or taken: the list is read again and no longer shows the request.
                if (gone) incoming.reloadAndWait()
                return@runGuardedAction
            }
            incoming.reloadAndWait()
            if (accept) {
                connections.reloadAndWait()
                page.announce(tr("Verbindung hergestellt."))
                answer.connection?.let { focusConnection(it.connectionId) }
            } else {
                if (block) blocks.reloadAndWait()
                page.announce(if (block) tr("Anfrage abgelehnt und blockiert.") else tr("Anfrage abgelehnt."))
            }
        }
    }

    private fun focusConnection(connectionId: String) {
        // The id is a random one and a plain attribute value; no selector injection is possible from a UUID, but the lookup is by exact match anyway.
        val items = document.querySelectorAll("[data-connection-id]")
        for (i in 0 until items.length) {
            val item = items.item(i) as? HTMLElement ?: continue
            if (item.getAttribute("data-connection-id") == connectionId) {
                (item.querySelector("h3") as? HTMLElement)?.focus()
                return
            }
        }
    }

    // ---- 3. connections ----

    private fun renderConnections(
        host: SimplePanel,
        items: List<MemberFinderConnectionDto>,
    ) {
        if (items.isEmpty()) return
        host.h2(tr("Verbindungen")) { addCssClass("h5") }
        val list = host.tag(TAG.UL, className = "list-unstyled d-flex flex-column gap-2 mb-0")
        items.forEach { connection ->
            val item = list.tag(TAG.LI, className = "border rounded p-3")
            item.setAttribute("data-connection-id", connection.connectionId)
            val heading = item.untrustedHeading(connection.otherDisplayName, level = 3, className = "h6 mb-1")
            heading.setAttribute("tabindex", "-1")
            item.div(memberFinderPurposeLabel(connection.purpose))
            item.div(gettext("Verbunden seit %1", formatDate(connection.connectedOn)), className = "text-muted small")
            item.div(gettext("Endet am %1", formatDate(connection.endsOn)), className = "text-muted small")
            val actions = item.div(className = "d-flex flex-wrap gap-2 mt-2")
            val message = actions.actionButton(ActionIcon.SEND, tr("Nachricht senden"), style = ButtonStyle.PRIMARY)
            message.onClick { showMessageDialog(connection.connectionId) }
            val block = actions.actionButton(ActionIcon.REVOKE, tr("Verbindung blockieren"), style = ButtonStyle.OUTLINEDANGER)
            block.onClick {
                confirmDialog(
                    title = tr("Verbindung blockieren?"),
                    message = tr("Die Verbindung wird beendet. Diese Person kann Ihnen keine Anfragen mehr schicken."),
                    confirmLabel = tr("Verbindung blockieren"),
                    focusCancel = true,
                ) {
                    AppScope.launch {
                        val done = act { service.blockConnection(connection.connectionId) }
                        if (done != null) {
                            connections.reloadAndWait()
                            blocks.reloadAndWait()
                            page.announce(tr("Verbindung blockiert."))
                        }
                    }
                }
            }
        }
    }

    private fun showMessageDialog(connectionId: String) {
        val modal = Modal(caption = tr("Nachricht senden"))
        val field = TextArea(rows = 4, label = tr("Ihre Nachricht"))
        field.input.setAttribute("maxlength", MESSAGE_MAX_LENGTH.toString())
        modal.add(field)
        val cancel = newActionButton(ActionIcon.CANCEL, tr("Abbrechen"), ButtonStyle.SECONDARY).apply { onClick { modal.hide() } }
        modal.addButton(cancel)
        val send = newActionButton(ActionIcon.SEND, tr("Senden"), ButtonStyle.PRIMARY)
        send.disabled = true
        field.subscribe { value -> send.disabled = value.orEmpty().isBlank() }
        val once = ConfirmOnce()
        send.onClick {
            val text = field.value.orEmpty().trim()
            if (text.isEmpty()) return@onClick
            once.run(send) {
                modal.hide()
                AppScope.launch {
                    // The answer carries both ids of the conversation; it is dropped on purpose.
                    val delivered = act { service.contactConnection(connectionId, text) }
                    if (delivered != null) {
                        val sentence = tr("Nachricht gesendet. Die Unterhaltung finden Sie unter Kommunikation.")
                        page.announce(sentence)
                        notifySuccess(sentence)
                    }
                }
            }
        }
        modal.addButton(send)
        modal.addAfterInsertHook { vnode ->
            (vnode.elm as? HTMLElement)?.addEventListener("shown.bs.modal", { field.input.getElement()?.focus() })
            kotlinx.browser.window.setTimeout({ field.input.getElement()?.focus() }, 0)
        }
        modal.show()
    }

    // ---- 4. send a request ----

    private fun renderSendForm() {
        val box = panel.vPanel(spacing = 8) { addCssClasses("border rounded p-3") }
        box.h2(tr("Anfrage senden")) { addCssClass("h5") }
        box.p(
            tr(
                "Ihre Anfrage geht an ein zufällig gewähltes Mitglied Ihrer Region, das Interesse hat. Die Person erfährt nicht, wer Sie sind, bevor sie zustimmt.",
            ),
            className = "text-muted small mb-0",
        )
        val purposes = memberFinderPurposesFor(state.interest)
        var purpose: MemberFinderPurpose? = purposes.singleOrNull()
        var choice: RadioGroup? = null
        if (purposes.size > 1) {
            val fieldset = Tag(TAG.FIELDSET, className = "border-0 p-0 m-0")
            fieldset.add(Tag(TAG.LEGEND, tr("Wofür?"), className = "h6 mb-1"))
            val radio =
                RadioGroup(
                    options = purposes.map { it.name to resolvedAttributeText(memberFinderPurposeLabel(it)) },
                    value = null,
                    name = "member-finder-purpose-${++finderRadioSerial}",
                )
            fieldset.add(radio)
            box.add(fieldset)
            choice = radio
        }
        val template = box.div(className = "fw-bold")

        fun refreshTemplate() {
            val current = purpose
            template.content = if (current != null) gettext("So lautet Ihre Anfrage: %1", memberFinderRequestTemplate(current)) else ""
        }
        refreshTemplate()
        val noteField = TextArea(rows = 2, label = tr("Notiz (freiwillig)"))
        noteField.input.setAttribute("aria-describedby", NOTE_HELP_ID)
        box.add(noteField)
        val help = box.div(className = "text-muted small")
        help.setAttribute("id", NOTE_HELP_ID)
        help.content = tr("Die Notiz sieht nur die angefragte Person. Bitte schreiben Sie keine persönlichen Daten hinein.")
        val counter = box.span("", className = "small")
        val pendingLine = box.div(className = "small")
        pendingText = pendingLine
        val failure = box.div(className = "text-danger")
        failure.setAttribute("role", "alert")
        val send = box.actionButton(ActionIcon.SEND, tr("Anfrage senden"), style = ButtonStyle.PRIMARY)
        val afterSend = box.div(className = "text-muted small")

        fun update() {
            val raw = noteField.value.orEmpty()
            val count = memberFinderNoteCount(raw)
            counter.content = gettext("%1/%2", count, MemberFinderRules.NOTE_MAX_LENGTH)
            if (count >= MEMBER_FINDER_NOTE_WARN_AT) counter.addCssClass("text-warning") else counter.removeCssClass("text-warning")
            pendingLine.content = gettext("%1 von %2 offenen Anfragen", state.pendingOutgoing, MemberFinderRules.MAX_OPEN_OUTGOING)
            send.disabled =
                purpose == null ||
                !memberFinderNoteSendable(raw) ||
                state.pendingOutgoing >= MemberFinderRules.MAX_OPEN_OUTGOING
        }
        sendButtonRefresh = { update() }
        update()
        noteField.subscribe { update() }
        choice?.subscribe { value ->
            purpose = MemberFinderPurpose.entries.firstOrNull { it.name == value }
            refreshTemplate()
            update()
        }

        send.onClick {
            runGuardedAction(send, restoreDisabled = {
                purpose == null ||
                    !memberFinderNoteSendable(noteField.value.orEmpty()) ||
                    state.pendingOutgoing >= MemberFinderRules.MAX_OPEN_OUTGOING
            }) {
                failure.content = ""
                afterSend.content = ""
                val picked = purpose ?: return@runGuardedAction
                val normalized = MemberFinderRules.normalizeNote(noteField.value)
                val answer = act(show = { failure.content = it }) { service.sendRequest(picked, normalized) }
                // Whatever the server found, the member gets the same answer; nothing of the response steers the page.
                if (answer == null) return@runGuardedAction
                outgoing.reloadAndWait()
                refreshState()
                page.announce(tr("Ihre Anfrage wurde gesendet."))
                afterSend.content = tr("Sie erfahren nur, wenn jemand zustimmt.")
                noteField.value = ""
            }
        }
    }

    // ---- 5. own requests ----

    private fun renderOutgoing(
        host: SimplePanel,
        items: List<MemberFinderOutgoingRequestDto>,
    ) {
        if (items.isEmpty()) return
        host.h2(tr("Gesendete Anfragen")) { addCssClass("h5") }
        val list = host.tag(TAG.UL, className = "list-unstyled d-flex flex-column gap-2 mb-0")
        items.forEach { request ->
            val item = list.tag(TAG.LI, className = "border rounded p-3")
            item.setAttribute("data-request-id", request.requestId)
            item.h3(memberFinderPurposeLabel(request.purpose), className = "h6 mb-1")
            item.div(gettext("Gesendet am %1", formatDate(request.sentOn)), className = "text-muted small")
            item.div(gettext("Läuft ab am %1", formatDate(request.expiresOn)), className = "text-muted small")
            item.div(memberFinderOutgoingStateLabel(request.state))
            if (request.state == MemberFinderOutgoingState.PENDING) {
                val withdraw = item.actionButton(ActionIcon.UNDO, tr("Zurückziehen"), style = ButtonStyle.OUTLINESECONDARY)
                withdraw.setAttribute("aria-label", gettext("Anfrage vom %1 zurückziehen", formatDate(request.sentOn)))
                withdraw.onClick {
                    runGuardedAction(withdraw) {
                        val done = act(onNotFound = {}) { service.withdrawRequest(request.requestId) }
                        outgoing.reloadAndWait()
                        if (done != null) {
                            refreshState()
                            page.announce(tr("Anfrage zurückgezogen."))
                        }
                    }
                }
            }
        }
    }

    // ---- 6. blocks ----

    private fun renderBlocks(
        host: SimplePanel,
        items: List<network.lapis.cloud.shared.domain.MemberFinderBlockDto>,
    ) {
        if (items.isEmpty()) return
        host.h2(tr("Blockierte")) { addCssClass("h5") }
        val list = host.tag(TAG.UL, className = "list-unstyled d-flex flex-column gap-2 mb-0")
        items.forEach { block ->
            val item = list.tag(TAG.LI, className = "border rounded p-3")
            item.setAttribute("data-block-id", block.blockId)
            item.div(gettext("Blockiert am %1", formatDate(block.createdOn)))
            val unblock = item.actionButton(ActionIcon.UNDO, tr("Entsperren"), style = ButtonStyle.OUTLINESECONDARY)
            unblock.setAttribute("aria-label", gettext("Blockierung vom %1 aufheben", formatDate(block.createdOn)))
            unblock.onClick {
                runGuardedAction(unblock) {
                    val done = act(onNotFound = {}) { service.unblock(block.blockId) }
                    blocks.reloadAndWait()
                    if (done != null) page.announce(tr("Entsperrt."))
                }
            }
        }
    }

    private companion object {
        const val NOTE_HELP_ID = "member-finder-note-help"
        const val STATUS_TITLE_ID = "member-finder-status-title"

        /** The server accepts up to 10 000 characters; the dialog stays far below that (a first message, not an essay). */
        const val MESSAGE_MAX_LENGTH = 2000
    }
}
