package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.button
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.EventGuestJoinRequestDto
import network.lapis.cloud.shared.domain.EventGuestJoinResponseDto
import network.lapis.cloud.shared.domain.EventGuestNotice
import network.lapis.cloud.shared.domain.EventGuestNoticeCapture
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import network.lapis.cloud.shared.domain.EventOnlineStateDto
import network.lapis.cloud.shared.domain.EventQuery
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.rpc.IEventService

/** The longest name the server accepts (mirrors `EVENT_GUEST_NAME_MAX_LENGTH`). */
internal const val EVENT_GUEST_NAME_MAX = 60

/** A name the form may send: 1..[EVENT_GUEST_NAME_MAX] characters after trimming. The server normalizes and re-checks. */
internal fun eventGuestNameOk(raw: String): Boolean {
    val trimmed = raw.trim()
    return trimmed.isNotEmpty() && trimmed.length <= EVENT_GUEST_NAME_MAX
}

/** Starts the call view for a successful answer inside [host]; injectable so a DOM test needs no LiveKit. [onLeft] returns to the join form. */
internal fun interface EventGuestCallStarter {
    fun start(
        host: SimplePanel,
        response: EventGuestJoinResponseDto,
        onLeft: () -> Unit,
    )
}

/** Route entry (`Routes.EVENT_JOIN`, `#/event-join/<slug>`): the public page to take part online in an event. */
fun renderEventJoinScreen(
    container: SimplePanel,
    slug: String?,
) {
    renderEventJoinScreenWith(
        root = container,
        slug = slug,
        api = EventGuestHttp,
        memberRoute = { eventSlug -> openMemberEventRoute(eventSlug) },
        startCall = EventGuestCallStarter { host, response, onLeft -> startEventGuestCall(host, response, onLeft) },
    )
}

/**
 * Welle V1.9.113 -- the page behind `#/event-join/<slug>`, the link on the public event page and the ticket. Two audiences, one page:
 *
 * - **A signed-in person** is sent straight on to the member way ([memberRoute] looks the event up and opens `#/conference-event/<id>`).
 * - **Everybody else** sees the state of the window (not yet / open / over), and when it is open a form: the name (visible to all), the
 *   one-sentence notice ([EventGuestNotice.TEXT]) with a confirmation box and -- for an event with a registration deadline -- the ticket
 *   code of the confirmed registration. The microphone and camera start OFF in the call.
 *
 * Nothing about the person is kept: no account, no cookie, no entry in the participant history. A refusal that the server words
 * identically for "unknown / hidden / not allowed" is shown identically here. All text from the server (the event title) is untrusted.
 */
internal fun renderEventJoinScreenWith(
    root: SimplePanel,
    slug: String?,
    api: EventGuestApi,
    memberRoute: suspend (String) -> Unit,
    startCall: EventGuestCallStarter,
) {
    val screen =
        root.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 640.px
            marginTop = 24.px
        }
    screen.pageHeader(tr("Online teilnehmen"))
    val body = screen.vPanel(spacing = 10)
    val status =
        screen.div {
            setAttribute("role", "status")
            setAttribute("aria-live", "polite")
            addCssClasses("text-muted small")
        }
    if (slug.isNullOrBlank()) {
        status.content = tr("Diese Veranstaltung wurde nicht gefunden.")
        return
    }

    // [message] is shown in the status line once the state is read again (the notice changed under the person's hands).
    lateinit var load: (String?) -> Unit
    load = { message ->
        body.removeAll()
        status.content = tr("Wird geladen …")
        AppScope.launch {
            if (AppState.isAuthenticated) {
                memberRoute(slug)
                status.content = ""
                return@launch
            }
            val state = api.state(slug)
            status.content = message ?: ""
            if (state == null) {
                body.div(
                    tr("Der Zugang konnte nicht geladen werden. Bitte versuchen Sie es erneut."),
                ) { addCssClasses("alert alert-warning") }
                body.button(tr("Erneut prüfen"), style = ButtonStyle.OUTLINESECONDARY) { onClick { load(null) } }
                return@launch
            }
            renderGuestState(
                body = body,
                status = status,
                slug = slug,
                state = state,
                api = api,
                startCall = startCall,
                reload = { notice -> load(notice) },
            )
        }
    }
    load(null)
}

private fun renderGuestState(
    body: SimplePanel,
    status: Div,
    slug: String,
    state: EventOnlineStateDto,
    api: EventGuestApi,
    startCall: EventGuestCallStarter,
    reload: (String?) -> Unit,
) {
    when (state.outcome) {
        EventOnlineOutcome.NOT_YET -> {
            state.title?.let { body.untrustedHeading(it, level = 2, className = "h5") }
            val opens = state.opensAt?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
            body.div(eventOnlineOutcomeMessage(EventOnlineOutcome.NOT_YET, opens).orEmpty()) { addCssClasses("alert alert-info") }
            body.button(tr("Erneut prüfen"), style = ButtonStyle.OUTLINESECONDARY) { onClick { reload(null) } }
        }
        EventOnlineOutcome.ENDED ->
            body.div(eventOnlineOutcomeMessage(EventOnlineOutcome.ENDED).orEmpty()) { addCssClasses("alert alert-secondary") }
        EventOnlineOutcome.JOINED ->
            renderGuestForm(
                body = body,
                status = status,
                slug = slug,
                state = state,
                api = api,
                startCall = startCall,
                reload = reload,
            )
        else ->
            // FORBIDDEN (unknown, hidden, members-only ... all the same words), UNAVAILABLE and the rest.
            body.div(eventOnlineOutcomeMessage(state.outcome).orEmpty()) { addCssClasses("alert alert-secondary") }
    }
}

private fun renderGuestForm(
    body: SimplePanel,
    status: Div,
    slug: String,
    state: EventOnlineStateDto,
    api: EventGuestApi,
    startCall: EventGuestCallStarter,
    reload: (String?) -> Unit,
) {
    state.title?.let { body.untrustedHeading(it, level = 2, className = "h5") }
    // The form grammar: required marks, one error slot per field, a collective message and a busy button that cannot be double-clicked.
    val form = body.lapisForm()
    val nameField =
        form.textField(
            label = tr("Ihr Name"),
            required = true,
            hint = tr("Mit diesem Namen werden Sie den anderen Teilnehmenden angezeigt."),
            rule = { value ->
                if (eventGuestNameOk(value)) FieldCheck.Ok else FieldCheck.Invalid(gettext("Bitte prüfen Sie Ihren Namen."))
            },
        )
    val ticketField =
        if (state.requiresTicket) {
            form.textField(
                label = tr("Ticket-Code"),
                required = true,
                hint = tr("Für diese Veranstaltung ist eine bestätigte Anmeldung nötig. Den Code finden Sie auf Ihrem Ticket."),
            )
        } else {
            null
        }
    // V1.9.156: an event that may be recorded or streamed shows the capture consent FIRST, with a box of its own, and then the capture variant of the
    // guest notice (the old sentence promises that nothing is recorded). The server tells which one applies (captureNotice) and re-checks both.
    val captureNotice = state.captureNotice
    val captureField =
        if (captureNotice != null) {
            form.panel.div(tr("Aufzeichnung und Übertragung in diesem Raum")) { addCssClasses("fw-bold") }
            form.panel.renderEventCaptureNotice(captureNotice)
            form.checkField(
                label = tr("Ich willige ein, dass mein Bild und mein Ton aufgezeichnet und live übertragen werden dürfen."),
                required = true,
                requiredMessage = tr("Bitte bestätigen Sie die Einwilligung, um teilzunehmen."),
            )
        } else {
            null
        }
    // The one sentence of the notice -- the acknowledged version/hash are those of [EventGuestNotice] (or [EventGuestNoticeCapture]), which the server compares.
    if (captureNotice != null) {
        form.panel.div(tr("Ihr Name ist für alle Teilnehmenden sichtbar. Ihr Mikrofon ist zu Beginn aus.")) {
            addCssClasses("alert alert-info mb-0")
            setAttribute("role", "note")
        }
    } else {
        form.panel.div(tr("Ihr Name ist für alle Teilnehmenden sichtbar. Ihr Mikrofon ist zu Beginn aus. Es wird nichts aufgezeichnet.")) {
            addCssClasses("alert alert-info mb-0")
            setAttribute("role", "note")
        }
    }
    form.checkField(
        label = tr("Ich habe den Hinweis gelesen."),
        required = true,
        requiredMessage = tr("Bitte bestätigen Sie den Hinweis."),
    )
    val joinButton = Button(tr("Beitreten"), style = ButtonStyle.PRIMARY)
    form.buttons(primary = joinButton, enterSubmits = true)

    joinButton.onClick {
        form.submit(joinButton) {
            val request =
                EventGuestJoinRequestDto(
                    name = nameField.value.trim(),
                    consentVersion = if (captureNotice != null) EventGuestNoticeCapture.VERSION else EventGuestNotice.VERSION,
                    consentSha256 = if (captureNotice != null) EventGuestNoticeCapture.SHA256 else EventGuestNotice.SHA256,
                    ticketCode = ticketField?.value?.trim()?.takeIf { it.isNotEmpty() },
                    captureConsentVersion = captureNotice?.versionLabel,
                    captureConsentSha256 = captureNotice?.sha256,
                )
            var result = api.join(slug, request)
            var attempts = 0
            // The room is being created by another request: ask again a few times, one second apart.
            while (attempts < GUEST_PREPARING_RETRIES &&
                (result as? EventGuestJoinHttpResult.Answer)?.response?.outcome == EventOnlineOutcome.PREPARING
            ) {
                attempts++
                status.content = eventOnlineOutcomeMessage(EventOnlineOutcome.PREPARING).orEmpty()
                delay(GUEST_PREPARING_RETRY_MS)
                result = api.join(slug, request)
            }
            status.content = ""
            when (result) {
                is EventGuestJoinHttpResult.Answer -> {
                    val response = result.response
                    if (response.outcome == EventOnlineOutcome.JOINED && response.token != null) {
                        form.panel.hide()
                        val callHost = body.vPanel(spacing = 8)
                        startCall.start(callHost, response) {
                            callHost.removeAll()
                            form.panel.show()
                        }
                    } else {
                        val opens = response.opensAt?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                        form.showFormError(eventOnlineOutcomeMessage(response.outcome, opens).orEmpty())
                    }
                }
                EventGuestJoinHttpResult.RateLimited ->
                    form.showFormError(gettext("Zu viele Anfragen. Bitte warten Sie einen Moment und versuchen Sie es erneut."))
                EventGuestJoinHttpResult.NoticeOutdated -> {
                    // V1.9.156: the notice (or the release of the event) changed since this page was loaded: read the state again, so the person sees
                    // exactly what is asked now. A name typed in has to be typed again; nothing was sent.
                    reload(gettext("Der Hinweis wurde geändert. Bitte lesen und bestätigen Sie ihn erneut."))
                }
                EventGuestJoinHttpResult.Invalid -> form.showFormError(gettext("Bitte prüfen Sie Ihren Namen."))
                EventGuestJoinHttpResult.Failed ->
                    form.showFormError(gettext("Die Teilnahme ist gerade nicht möglich. Bitte versuchen Sie es erneut."))
            }
        }
    }
}

private const val GUEST_PREPARING_RETRIES = 3
private const val GUEST_PREPARING_RETRY_MS = 1000L

/**
 * `#/event-join/<slug>` for a signed-in person: resolves the published event by its slug (server-side, so the 60-minute trail after the end still works) and continues at
 * `#/conference-event/<id>`. An event that is not in the list (not visible to this member) ends in the same words as an unknown one.
 */
internal suspend fun openMemberEventRoute(slug: String) {
    val page =
        guarded {
            rpcService<IEventService>().listEvents(
                EventQuery(status = EventStatus.PUBLISHED, includePast = true, limit = 1, slug = slug),
            )
        }
    val event = page?.rows?.firstOrNull { it.slug == slug }
    if (event == null) {
        notifyError(tr("Diese Veranstaltung wurde nicht gefunden."))
        navigateTo(Routes.MY_EVENTS)
    } else {
        navigateTo("${Routes.EVENT_CONFERENCE_BASE}/${event.id}")
    }
}
