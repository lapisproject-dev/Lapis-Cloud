package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.html.div
import io.kvision.html.p
import io.kvision.html.span
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.browser.sessionStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AuctionHintResolutionDto
import network.lapis.cloud.shared.domain.AuctionTeaser
import network.lapis.cloud.shared.rpc.IAuctionService
import kotlin.js.Date

/*
 * V1.9.322 (step c2) -- the landing page of a hint link `https://<origin>/app#/auction/hint/<id>`. The id sits in the hash fragment, so it reaches
 * neither server logs nor the Referer. The page asks the server ONE question (`resolveAuctionHint`); everything that is not a plainly open, shared
 * Euro auction gets the SAME neutral page (no hint whether the id never existed, is over, was withdrawn or the exchange is off). The id is never
 * shown (not in the title, not in an aria label, not in a toast, not in the console) and never handed to another call.
 *
 * Like the room-poll viewer, a login on the way back is bridged by a very narrow `sessionStorage` entry: only a strictly validated 64-hex id with a
 * time stamp, used once. `safeReturnTo` stays untouched (security-critical); the target is REBUILT from the validated id, the stored text is never
 * used as a route.
 */

internal const val AUCTION_HINT_RETURN_KEY = "lapis.pendingAuctionHint"
internal const val AUCTION_HINT_RETURN_TTL_MS = 30 * 60 * 1000.0
private const val SEPARATOR = "|"

internal fun isAuctionHintId(value: String?): Boolean = value != null && AuctionTeaser.TEASER_ID_REGEX.matches(value)

/** Remembers [teaserId] (only if it is a valid hint id) for the next login. */
internal fun rememberAuctionHintReturn(
    teaserId: String?,
    nowMs: Double = Date.now(),
) {
    if (!isAuctionHintId(teaserId)) return
    try {
        sessionStorage.setItem(AUCTION_HINT_RETURN_KEY, "$teaserId$SEPARATOR${nowMs.toLong()}")
    } catch (ignored: Throwable) {
        // blocked storage: the login simply does not return to the page (accepted)
    }
}

/** Reads and removes the remembered id; `null` when there is none, it is older than 30 minutes or it is not a valid id. */
internal fun consumeAuctionHintReturn(nowMs: Double = Date.now()): String? {
    val stored =
        try {
            sessionStorage.getItem(AUCTION_HINT_RETURN_KEY)
        } catch (e: Throwable) {
            null
        } ?: return null
    try {
        sessionStorage.removeItem(AUCTION_HINT_RETURN_KEY)
    } catch (ignored: Throwable) {
        // nothing more to do
    }
    val parts = stored.split(SEPARATOR)
    if (parts.size != 2) return null
    val savedAt = parts[1].toLongOrNull() ?: return null
    val age = nowMs - savedAt
    if (age < 0 || age > AUCTION_HINT_RETURN_TTL_MS) return null
    return parts[0].takeIf { isAuctionHintId(it) }
}

/** The route of the landing page, rebuilt from a validated id (never from stored text). */
internal fun auctionHintRoute(teaserId: String): String = "/auction/hint/$teaserId"

/** After a login or on the start page: the landing page if a return is remembered, else `false` (the caller goes to its default). */
internal fun continueToAuctionHint(): Boolean {
    val id = consumeAuctionHintReturn() ?: return false
    navigateTo(auctionHintRoute(id))
    return true
}

/** The ONE neutral page for every case that is not "open and shared". Identical text for all of them. */
internal fun renderAuctionHintUnavailable(container: SimplePanel) {
    val root = container.pageShell(PageWidth.FORM, spacing = 12)
    root.pageHeader(tr("Auktionshinweis"))
    root.p(tr("Diese Auktion ist nicht (mehr) verfügbar.")) { addCssClasses("text-muted") }
    val toAuctions = newActionButton(ActionIcon.VIEW, tr("Zu den Auktionen"), ButtonStyle.PRIMARY)
    toAuctions.onClick { navigateTo(Routes.AUCTION) }
    root.add(toAuctions)
}

fun renderAuctionHintScreen(
    container: SimplePanel,
    teaserId: String?,
) {
    val host = container.vPanel(spacing = 8)
    // A spinner without text of its own: the only words are visually hidden status words that name no id.
    host.div { addCssClasses("text-center p-4") }.apply {
        setAttribute("role", "status")
        setAttribute("aria-busy", "true")
        span(className = "spinner-border") { setAttribute("aria-hidden", "true") }
        span(tr("Wird geladen …"), className = "visually-hidden")
    }
    AppScope.launch {
        val resolution =
            if (!isAuctionHintId(teaserId)) {
                AuctionHintResolutionDto.Unavailable
            } else {
                try {
                    rpcService<IAuctionService>().resolveAuctionHint(teaserId.orEmpty())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // The same page as "not available": a failure must not tell a prober anything either.
                    AuctionHintResolutionDto.Unavailable
                }
            }
        host.removeAll()
        when (resolution) {
            is AuctionHintResolutionDto.Available -> renderAuctionScreenWithFocus(host, resolution.auctionId)
            AuctionHintResolutionDto.Unavailable -> renderAuctionHintUnavailable(host)
        }
    }
}
