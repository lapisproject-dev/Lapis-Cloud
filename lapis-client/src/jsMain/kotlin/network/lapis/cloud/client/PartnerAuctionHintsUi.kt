package network.lapis.cloud.client

import io.kvision.core.Container
import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AuctionTeaserPriceKind
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PartnerAuctionHintDto
import network.lapis.cloud.shared.domain.PartnerAuctionHintLimits
import network.lapis.cloud.shared.domain.PartnerAuctionHintPageDto
import network.lapis.cloud.shared.domain.PartnerAuctionHintRules
import network.lapis.cloud.shared.domain.buildAuctionHintLink
import network.lapis.cloud.shared.domain.mayShowPartnerAuctionHints
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/*
 * V1.9.322 "Auktionen auf Partnerservern" step c3 -- the auctions that PARTNER servers show to our ACTIVE members, below the own auction list. Each
 * card shows what the hint carries (origin host, title, price with its "Stand", a Sofortkauf line, the end, the offers badge) and ONE link to the
 * origin server, where bidding and buying happen. There is nothing to bid on here: no bid or buy button, the card itself is not clickable.
 *
 * Every foreign value (host, title) goes through the `untrusted*` helpers; the title never reaches an attribute, a toast or the document title.
 * Nothing is requested for anybody who may not see the section (no membership, switch off, auction module off): the availability call decides.
 * The end and the "Stand" are recomputed once a minute (a hint that ends disappears from the screen without a reload).
 */

/** The DOM-free decisions of the section: German source texts only; foreign values are arguments of the texts, never part of a msgid. */
internal object PartnerAuctionHintsUiLogic {
    /** The minute tick of the section. */
    const val TICK_MS = 60_000

    fun priceLabel(kind: AuctionTeaserPriceKind): String =
        when (kind) {
            AuctionTeaserPriceKind.START -> gettext("Startpreis")
            AuctionTeaserPriceKind.CURRENT -> gettext("Aktueller Preis")
        }

    /** Exact to the cent. A currency this server does not know shows no number (unreachable: the server only passes the supported ones). */
    fun formatPrice(
        minor: Long,
        currency: String,
    ): String = formatAuctionAmount(currency = currency, ltr = null, minor = minor)

    /** Unreachable (the server refuses LTR hints); kept and tested so that a leak could never read as a number. */
    fun ltrFallback(host: String): String = gettext("LTR von %1", host)

    /** `null` for a start price (nothing to age); otherwise how old the shown price is, floored. */
    fun asOfText(
        kind: AuctionTeaserPriceKind,
        priceAsOf: Instant,
        now: Instant,
    ): String? {
        if (kind == AuctionTeaserPriceKind.START) return null
        val age = now - priceAsOf
        return when {
            age < 1.minutes -> gettext("· Stand: gerade eben")
            age < 60.minutes -> gettext("· Stand: vor %1 Min.", age.inWholeMinutes)
            else -> gettext("· Stand: vor %1 Std.", age.inWholeHours)
        }
    }

    /** From 60 minutes left the end is a date and time of the organisation's zone; below that it is a countdown in minutes. */
    fun endText(
        endsAt: Instant,
        now: Instant,
    ): String {
        val left = endsAt - now
        return when {
            left >= 60.minutes -> gettext("endet am %1", formatSystemDateTime(endsAt.toLocalDateTime(TimeZone.UTC)))
            left < 1.minutes -> gettext("endet in weniger als 1 Min.")
            else -> gettext("endet in %1 Min.", left.inWholeMinutes)
        }
    }

    /** What is still on show: a hint is gone the moment its end is reached, the row may still exist on the server until the sweep. */
    fun visible(
        items: List<PartnerAuctionHintDto>,
        now: Instant,
    ): List<PartnerAuctionHintDto> = items.filter { PartnerAuctionHintRules.isShowable(endsAt = it.endsAt, now = now) }

    /** The client never asks for a page the server would refuse. */
    fun canLoadMore(
        hasMore: Boolean,
        page: Int,
    ): Boolean = hasMore && page < PartnerAuctionHintLimits.MAX_PAGE

    fun showBadge(dto: PartnerAuctionHintDto): Boolean = dto.offersPossible

    fun buyNowLine(dto: PartnerAuctionHintDto): String? = dto.buyNowMinor?.let { gettext("Sofortkauf %1", formatPrice(it, dto.currency)) }

    /** The link to the origin, or `null` (host with a port, an IP, upper case, ...): then the card has no button. */
    fun link(dto: PartnerAuctionHintDto): String? = buildAuctionHintLink(originHost = dto.originHost, teaserId = dto.teaserId)

    fun viewLabel(host: String): String = gettext("Auf %1 ansehen", host)

    fun viewAria(host: String): String = gettext("Auf %1 ansehen (öffnet neues Fenster)", host)
}

/** What the list shows right now. */
internal sealed interface PartnerAuctionHintsState {
    data object Loading : PartnerAuctionHintsState

    /** [items] stay visible; [target] is the page that failed and the one a retry asks for. */
    class Failed(
        val items: List<PartnerAuctionHintDto>,
        val target: Int,
    ) : PartnerAuctionHintsState

    class Loaded(
        val items: List<PartnerAuctionHintDto>,
        val hasMore: Boolean,
        val page: Int,
    ) : PartnerAuctionHintsState
}

/**
 * The section. Built only after the exchange is known to be visible ([mountPartnerAuctionHints]). [loadPage] and [hide] are the RPCs (lambdas, so a
 * test can feed them); [clock] and [tickMs] let a test drive the minute tick. Known limit of offset paging: rows can shift between two requests,
 * which can skip or repeat an entry.
 */
internal class PartnerAuctionHintsSection(
    container: Container,
    private val loadPage: suspend (Int) -> PartnerAuctionHintPageDto,
    private val isAdmin: Boolean = false,
    private val hide: suspend (String, String) -> Unit = { _, _ -> },
    private val clock: () -> Instant = { Clock.System.now() },
    private val tickMs: Int = PartnerAuctionHintsUiLogic.TICK_MS,
) {
    private val box = container.vPanel(spacing = 4)
    private val listBox: SimplePanel
    private val footer: SimplePanel
    private val items = mutableListOf<PartnerAuctionHintDto>()
    private var page = 0
    private var hasMore = false
    private var failed = false
    private var failedTarget = 0
    private var loading = false
    private var timerId: Int? = null

    init {
        box.h2(tr("Auktionen von Partnerservern")) { addCssClass("h5") }
        box.div(
            tr("Kurzhinweise von Partnerservern: Titel, Preis und Ende. Bieten und Kaufen ist nur beim anbietenden Server möglich."),
            className = "text-muted small",
        )
        listBox = SimplePanel()
        footer = SimplePanel()
        // The list and its footer live in one wrapper with the lifecycle of the minute tick: it starts when the section really is in the document
        // and stops when it leaves (a screen has no unmount hook of its own).
        val wrapper = SimplePanel()
        wrapper.add(listBox)
        wrapper.add(footer)
        box.addWithLifecycle(
            wrapper,
            onInsert = {
                if (timerId == null) timerId = window.setInterval({ renderList() }, tickMs)
            },
            onDestroy = {
                timerId?.let { window.clearInterval(it) }
                timerId = null
            },
        )
    }

    /** The first page. */
    fun start() {
        items.clear()
        page = 0
        hasMore = false
        fetch(0)
    }

    private fun fetch(target: Int) {
        if (loading) return
        loading = true
        failed = false
        renderList()
        AppScope.launch {
            val answer = runCatching { loadPage(target) }.getOrNull()
            loading = false
            if (answer == null) {
                failed = true
                failedTarget = target
            } else {
                if (target == 0) items.clear()
                items += answer.items
                page = target
                hasMore = answer.hasMore
            }
            renderList()
        }
    }

    /** Draws the current state; synchronous, so a test can drive it with [showState]. */
    internal fun renderList() {
        val state: PartnerAuctionHintsState =
            when {
                loading && items.isEmpty() -> PartnerAuctionHintsState.Loading
                failed -> PartnerAuctionHintsState.Failed(items = items.toList(), target = failedTarget)
                else -> PartnerAuctionHintsState.Loaded(items = items.toList(), hasMore = hasMore, page = page)
            }
        showState(state)
    }

    internal fun showState(state: PartnerAuctionHintsState) {
        listBox.removeAll()
        footer.removeAll()
        when (state) {
            PartnerAuctionHintsState.Loading ->
                listBox.div(tr("Die Hinweise werden geladen …"), className = "text-muted small") { setAttribute("role", "status") }
            is PartnerAuctionHintsState.Failed -> {
                renderItems(state.items)
                footer.div(
                    tr("Die Hinweise konnten nicht geladen werden."),
                    className = "text-danger small",
                ) { setAttribute("role", "alert") }
                val retry = newActionButton(ActionIcon.REFRESH, tr("Erneut versuchen"))
                retry.onClick { fetch(state.target) }
                footer.add(retry)
            }
            is PartnerAuctionHintsState.Loaded -> {
                renderItems(state.items)
                if (PartnerAuctionHintsUiLogic.canLoadMore(hasMore = state.hasMore, page = state.page)) {
                    val more = newActionButton(ActionIcon.NEXT, tr("Weitere laden"))
                    more.onClick { fetch(state.page + 1) }
                    footer.add(more)
                }
            }
        }
    }

    private fun renderItems(all: List<PartnerAuctionHintDto>) {
        val now = clock()
        val shown = PartnerAuctionHintsUiLogic.visible(items = all, now = now)
        if (shown.isEmpty()) listBox.div(tr("Noch keine Auktionen von Partnerservern."), className = "text-muted small")
        shown.forEach { renderPartnerAuctionCard(into = listBox, dto = it, now = now, isAdmin = isAdmin, onHide = ::hideOne) }
    }

    private fun hideOne(dto: PartnerAuctionHintDto) {
        confirmDialog(
            title = tr("Hinweis ausblenden?"),
            message =
                gettext(
                    "Der Hinweis von %1 verschwindet bei uns und kommt nicht wieder, auch wenn der Partnerserver ihn erneut meldet. Der Partnerserver erfährt davon nichts.",
                    sanitizeUntrustedI18nText(dto.originHost),
                ),
            confirmLabel = tr("Ausblenden"),
            confirmIcon = ActionIcon.HIDE_HINT,
        ) {
            AppScope.launch {
                val ok = runCatching { hide(dto.originHost, dto.teaserId) }.isSuccess
                if (ok) {
                    items.removeAll { it.originHost == dto.originHost && it.teaserId == dto.teaserId }
                    notifySuccess(gettext("Hinweis ausgeblendet."))
                    renderList()
                } else {
                    notifyError(tr("Der Hinweis konnte nicht ausgeblendet werden."))
                }
            }
        }
    }
}

/**
 * ONE card. The same geometry as an auction card of this server, with the dashed accent border (`lapis-card-federated`) that says "this is
 * somebody else's": the origin host first, then title, price, Sofortkauf line, end, badge and the single link. Nothing on it is a bid or a buy.
 */
internal fun renderPartnerAuctionCard(
    into: SimplePanel,
    dto: PartnerAuctionHintDto,
    now: Instant,
    isAdmin: Boolean,
    onHide: (PartnerAuctionHintDto) -> Unit,
) {
    val card = into.vPanel(spacing = 2) { addCssClasses("border rounded p-3 lapis-card-federated") }
    val origin = card.div(className = "d-flex align-items-center gap-2 text-muted small")
    origin.span(className = ActionIcon.FEDERATED.css) { setAttribute("aria-hidden", "true") }
    origin.untrustedSpan(dto.originHost, className = "text-truncate")
    card.untrustedDiv(dto.title, className = "fw-bold lapis-line-clamp-2")
    val priceRow = card.div(className = "d-flex flex-wrap gap-2")
    priceRow.div(PartnerAuctionHintsUiLogic.priceLabel(dto.priceKind), className = "text-muted")
    priceRow.div(PartnerAuctionHintsUiLogic.formatPrice(dto.priceMinor, dto.currency), className = "fw-bold")
    PartnerAuctionHintsUiLogic.asOfText(dto.priceKind, dto.priceAsOf, now)?.let { priceRow.div(it, className = "text-muted small") }
    PartnerAuctionHintsUiLogic.buyNowLine(dto)?.let { card.div(it) }
    card.div(PartnerAuctionHintsUiLogic.endText(dto.endsAt, now), className = "text-muted small")
    if (PartnerAuctionHintsUiLogic.showBadge(
            dto,
        )
    ) {
        card.div(tr("Preisvorschlag möglich"), className = "badge text-bg-secondary align-self-start")
    }
    val actions = card.div(className = "d-flex flex-wrap gap-2 align-items-center")
    PartnerAuctionHintsUiLogic.link(dto)?.let { href ->
        val a =
            actions.link(
                label = PartnerAuctionHintsUiLogic.viewLabel(sanitizeUntrustedI18nText(dto.originHost)),
                url = href,
                icon = ActionIcon.OPEN_EXTERNAL.css,
                className = "btn btn-outline-primary",
            )
        a.setAttribute("target", "_blank")
        a.setAttribute("rel", "noopener noreferrer")
        a.setAttribute("referrerpolicy", "no-referrer")
        a.setAttribute("aria-label", PartnerAuctionHintsUiLogic.viewAria(sanitizeUntrustedI18nText(dto.originHost)))
    }
    if (isAdmin) {
        val hide = newActionButton(ActionIcon.HIDE_HINT, tr("Hinweis ausblenden"), ButtonStyle.OUTLINESECONDARY, small = true)
        hide.onClick { onHide(dto) }
        actions.add(hide)
    }
}

/**
 * Mounts the section below [container] only for an ACTIVE member and only after the availability call says the partner auctions are visible. A
 * failure of that call counts as "not visible". `loadPage` is called in NO other case.
 */
internal fun mountPartnerAuctionHints(
    container: Container,
    status: MemberStatus?,
    isAdmin: Boolean,
    readVisible: suspend () -> Boolean,
    loadPage: suspend (Int) -> PartnerAuctionHintPageDto,
    hide: suspend (String, String) -> Unit,
) {
    if (!mayShowPartnerAuctionHints(status = status, partnerHintsVisible = true)) return
    val slot = container.vPanel()
    AppScope.launch {
        val visible = runCatching { readVisible() }.getOrDefault(false)
        if (mayShowPartnerAuctionHints(status = status, partnerHintsVisible = visible)) {
            PartnerAuctionHintsSection(container = slot, loadPage = loadPage, isAdmin = isAdmin, hide = hide).start()
        }
    }
}
