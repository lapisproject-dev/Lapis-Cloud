package network.lapis.cloud.client

import io.kvision.core.onClick
import io.kvision.html.Button
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.hPanel
import io.kvision.panel.vPanel
import io.kvision.utils.px
import kotlinx.browser.window
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventPageDto
import network.lapis.cloud.shared.domain.EventQuery
import network.lapis.cloud.shared.domain.EventRegistrationDto
import network.lapis.cloud.shared.domain.EventRegistrationResultDto
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.rpc.IEventService
import org.w3c.dom.url.URL
import kotlin.time.Clock

/** The RPC surface the member event screen needs -- an interface so DOM tests can drive it without a server. Exceptions propagate. */
internal interface MemberEventsRpc {
    suspend fun listUpcoming(): EventPageDto

    suspend fun registerSelf(eventId: String): EventRegistrationResultDto

    suspend fun cancelOwnRegistration(eventId: String): EventRegistrationDto

    suspend fun resumeOwnEventPayment(eventId: String): EventRegistrationResultDto
}

internal fun liveMemberEventsRpc(): MemberEventsRpc =
    object : MemberEventsRpc {
        override suspend fun listUpcoming() =
            rpcService<IEventService>().listEvents(
                EventQuery(status = EventStatus.PUBLISHED, includePast = false, limit = MEMBER_EVENTS_LIMIT),
            )

        override suspend fun registerSelf(eventId: String) = rpcService<IEventService>().registerSelf(eventId)

        override suspend fun cancelOwnRegistration(eventId: String) = rpcService<IEventService>().cancelOwnRegistration(eventId)

        override suspend fun resumeOwnEventPayment(eventId: String) = rpcService<IEventService>().resumeOwnEventPayment(eventId)
    }

/** The member list is capped, there is no paging here (documented limitation). */
internal const val MEMBER_EVENTS_LIMIT = 50

/** Injectable confirmation dialog (the tests drive it without a modal). */
internal fun interface MemberEventConfirm {
    fun show(
        title: String,
        message: String,
        confirmLabel: String,
        extraLines: List<String>,
        onConfirm: () -> Unit,
    )
}

internal val liveMemberEventConfirm =
    MemberEventConfirm { title, message, confirmLabel, extraLines, onConfirm ->
        confirmDialog(
            title = title,
            message = message,
            confirmLabel = confirmLabel,
            confirmStyle = ButtonStyle.DANGER,
            extraLines = extraLines,
            focusCancel = true,
            onConfirm = onConfirm,
        )
    }

/** What a member may do about an event's registration right now. */
internal sealed interface MemberEventAction {
    data object Register : MemberEventAction

    data object Waitlist : MemberEventAction

    data class RegisterAndPay(
        val amountText: String,
    ) : MemberEventAction

    data object None : MemberEventAction
}

private val ACTIVE_OWN_STATUSES =
    setOf(EventRegistrationStatus.CONFIRMED, EventRegistrationStatus.WAITLISTED, EventRegistrationStatus.PENDING_PAYMENT)

/** `true` iff the member has an active registration they may withdraw. */
internal fun canCancelOwn(e: EventDto): Boolean = e.ownRegistrationStatus in ACTIVE_OWN_STATUSES

private fun hasFee(e: EventDto): Boolean = e.feeAmount > 0.0

/**
 * The registration button for [e]. Registration closes with [EventDto.registrationClosesAt] AND with the start of the event
 * (the server's `EventPolicy.isRegistrationOpen` is `false` once `now >= startsAt`); an active own registration leaves nothing to
 * register. A full event offers the waitlist (the server never charges for it), a fee-bearing one "register and pay".
 */
internal fun memberEventAction(
    e: EventDto,
    now: LocalDateTime,
): MemberEventAction {
    val closesAt = e.registrationClosesAt
    return when {
        canCancelOwn(e) -> MemberEventAction.None
        now >= e.startsAt -> MemberEventAction.None
        closesAt != null && now > closesAt -> MemberEventAction.None
        e.full -> MemberEventAction.Waitlist
        hasFee(e) -> MemberEventAction.RegisterAndPay(formatMoney(e.feeAmount))
        else -> MemberEventAction.Register
    }
}

/** Only an absolute `https:` URL without credentials may be navigated to: no `http:`, `javascript:`, `data:` or scheme-relative `//host`. */
internal fun isSafeHttpsRedirect(url: String): Boolean {
    val parsed = runCatching { URL(url) }.getOrNull() ?: return false
    return parsed.protocol == "https:" && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.hostname.isNotEmpty()
}

internal fun clientNow(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

private fun ownStatusLabel(status: EventRegistrationStatus): String? =
    when (status) {
        EventRegistrationStatus.CONFIRMED -> gettext("Angemeldet")
        EventRegistrationStatus.WAITLISTED -> gettext("Warteliste")
        EventRegistrationStatus.PENDING_PAYMENT -> gettext("Zahlung offen")
        EventRegistrationStatus.CANCELLED, EventRegistrationStatus.EXPIRED -> null
    }

private fun ownStatusColor(status: EventRegistrationStatus): String =
    when (status) {
        EventRegistrationStatus.CONFIRMED -> "success"
        else -> "warning"
    }

/** Route entry (`Routes.MY_EVENTS`): the member's view of the upcoming events. */
fun renderMemberEventsScreen(container: SimplePanel) {
    renderMemberEventsScreenWith(
        root = container,
        rpc = liveMemberEventsRpc(),
        now = ::clientNow,
        navigate = { url -> window.location.assign(url) },
    )
}

/**
 * Welle V1.9.33 -- upcoming events for members: register, register and pay, join the waitlist, withdraw. The list loads through a
 * [dataSection]; every write is a [runGuardedAction] followed by a reload, so the screen always shows the server state.
 *
 * V1.9.35: an unfinished payment can be resumed here ("Zahlung fortsetzen", amount from the server); a paid withdrawal shows the
 * refund state (see `MemberEventPaymentUi.kt`). Honest limits: the reservation still expires on its own, and withdrawal never
 * refunds automatically -- the board pays outside Lapis Cloud and marks it. Titles, places and all other event text are untrusted
 * and only ever reach the screen through the `untrusted*` helpers. The payment redirect is followed only for `https:` URLs.
 */
internal fun renderMemberEventsScreenWith(
    root: SimplePanel,
    rpc: MemberEventsRpc,
    now: () -> LocalDateTime,
    navigate: (String) -> Unit,
    confirm: MemberEventConfirm = liveMemberEventConfirm,
    toastError: (String) -> Unit = { notifyError(it) },
    toastSuccess: (String) -> Unit = { notifySuccess(it) },
) {
    val screen =
        root.vPanel(spacing = 14) {
            addCssClasses("mx-auto w-100 px-3")
            maxWidth = 800.px
            marginTop = 24.px
        }
    screen.pageHeader(tr("Veranstaltungen"))
    lateinit var section: DataSection
    section =
        screen.dataSection<List<EventDto>>(
            emptyText = tr("Derzeit sind keine Veranstaltungen geplant."),
            isEmpty = { it.isEmpty() },
            load = {
                guarded { rpc.listUpcoming() }?.let { page ->
                    val current = now()
                    page.rows.filter { it.status == EventStatus.PUBLISHED && it.endsAt >= current }.sortedBy { it.startsAt }
                }
            },
            render = { panel, events ->
                val list = panel.vPanel(spacing = 10)
                events.forEach { e ->
                    renderEventCard(list, e, rpc, now, navigate, confirm, toastError, toastSuccess) { section.reload() }
                }
            },
        )
    section.reload()
}

private fun renderEventCard(
    parent: SimplePanel,
    e: EventDto,
    rpc: MemberEventsRpc,
    now: () -> LocalDateTime,
    navigate: (String) -> Unit,
    confirm: MemberEventConfirm,
    toastError: (String) -> Unit,
    toastSuccess: (String) -> Unit,
    reload: () -> Unit,
) {
    val card = parent.vPanel(spacing = 4) { addCssClasses("border rounded p-3") }
    val header = card.hPanel(spacing = 8) { addCssClasses("align-items-center") }
    header.untrustedCardTitle(e.title)
    val own = e.ownRegistrationStatus
    val ownLabel = own?.let { ownStatusLabel(it) }
    if (own != null && ownLabel != null) header.statusBadge(ownLabel, ownStatusColor(own))
    if (e.full && !canCancelOwn(e)) header.statusBadge(gettext("Ausgebucht"), "secondary")

    card.div(formatDateTime(e.startsAt)) { addCssClasses("text-muted small") }
    val place = e.locationText?.takeIf { it.isNotBlank() } ?: e.roomName?.takeIf { it.isNotBlank() }
    if (place != null) {
        card.untrustedDiv(place) { addCssClasses("small") }
    } else if (e.onlineUrl != null) {
        // never rendered as a link: the URL is untrusted
        card.div(tr("Online")) { addCssClasses("small") }
    }
    if (hasFee(e)) {
        card.div(gettext("Gebühr: %1", formatMoney(e.feeAmount))) { addCssClasses("small") }
    } else {
        card.div(tr("Kostenlos")) { addCssClasses("small") }
    }
    if (own == EventRegistrationStatus.PENDING_PAYMENT) {
        card.div(
            tr("Die Reservierung verfällt automatisch, wenn die Zahlung nicht abgeschlossen wird. Danach können Sie sich erneut anmelden."),
        ) { addCssClasses("text-muted small") }
    }
    card.renderOwnRefundLine(e)

    val actions = card.hPanel(spacing = 8)
    renderResumePaymentButton(actions, e, rpc, navigate, toastError, reload)
    val action = memberEventAction(e, now())
    if (action != MemberEventAction.None) {
        val label =
            when (action) {
                MemberEventAction.Register -> gettext("Zur Veranstaltung anmelden")
                MemberEventAction.Waitlist -> gettext("Auf die Warteliste")
                is MemberEventAction.RegisterAndPay -> gettext("Anmelden und bezahlen (%1)", action.amountText)
                MemberEventAction.None -> ""
            }
        val button = Button(label, style = ButtonStyle.PRIMARY)
        actions.add(button)
        button.onClick {
            runGuardedAction(button) {
                var result: EventRegistrationResultDto? = null
                val ok =
                    runOrConflict(
                        conflictMessage = gettext("Die Anmeldung war nicht möglich. Die Ansicht wurde aktualisiert."),
                        toast = toastError,
                    ) { result = rpc.registerSelf(e.id) }
                if (ok) {
                    val url = result?.checkoutRedirectUrl
                    if (url != null) {
                        if (isSafeHttpsRedirect(url)) {
                            navigate(url)
                            return@runGuardedAction
                        }
                        toastError(gettext("Die Zahlungsseite konnte nicht geöffnet werden."))
                    } else {
                        toastSuccess(
                            when (result?.registration?.status) {
                                EventRegistrationStatus.CONFIRMED -> gettext("Sie sind angemeldet.")
                                EventRegistrationStatus.WAITLISTED -> gettext("Sie stehen auf der Warteliste.")
                                else -> gettext("Ihre Anmeldung wurde gespeichert.")
                            },
                        )
                    }
                }
                reload()
            }
        }
    }
    if (canCancelOwn(e)) {
        val cancel = Button(tr("Von der Veranstaltung abmelden"), style = ButtonStyle.OUTLINEDANGER)
        actions.add(cancel)
        cancel.onClick {
            if (cancel.disabled) return@onClick
            val refundNote = memberWithdrawRefundNote(e)
            confirm.show(
                gettext("Anmeldung zurückziehen"),
                gettext(
                    "Möchten Sie Ihre Anmeldung zu „%1“ zurückziehen? Ihr Platz wird freigegeben.",
                    sanitizeUntrustedI18nText(e.title),
                ),
                gettext("Anmeldung zurückziehen"),
                refundNote,
            ) {
                runGuardedAction(cancel) {
                    runOrConflict(
                        conflictMessage = gettext("Ihre Anmeldung hatte sich bereits geändert. Die Ansicht wurde aktualisiert."),
                        toast = toastError,
                    ) { rpc.cancelOwnRegistration(e.id) }
                    reload()
                }
            }
        }
    }
}
