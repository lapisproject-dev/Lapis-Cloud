package network.lapis.cloud.client

import io.kvision.form.check.CheckBox
import io.kvision.form.check.checkBox
import io.kvision.html.ButtonStyle
import io.kvision.html.TAG
import io.kvision.html.div
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.shared.domain.AuctionCurrencies
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionFederationLimits
import network.lapis.cloud.shared.domain.AuctionFederationReachDto
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.AuctionTeaser
import network.lapis.cloud.shared.domain.AuctionTeaserPriceKind
import network.lapis.cloud.shared.domain.AuctionTeaserProjection
import network.lapis.cloud.shared.rpc.AuctionFederationEndsTooSoonException
import network.lapis.cloud.shared.rpc.AuctionFederationNotAllowedException
import network.lapis.cloud.shared.rpc.AuctionFederationShareLimitException
import network.lapis.cloud.shared.rpc.AuctionFederationTitleException
import network.lapis.cloud.shared.rpc.AuctionFederationUnavailableException
import network.lapis.cloud.shared.rpc.IAuctionService
import kotlin.time.Clock
import kotlin.time.Instant

/*
 * V1.9.322 (Fiat wave c, step c2) -- the seller's consent box for showing a Euro auction to partner servers: in the create form (Euro branch) and
 * on the seller's own card. The preview is computed by the SAME shared function the server uses for the feed (`AuctionTeaserProjection.project`):
 * what is shown here is what a partner receives. This file is the ONLY place in the client that calls it (pinned by AuctionFederationUiTripwireTest).
 * Titles are the seller's free text and always go through the `untrusted*` helpers.
 */

/** The DOM-free decisions of the consent box. */
internal object AuctionFederationUiLogic {
    /** The box is offered for a Euro auction that is still open and has time left while the exchange is on; an already shared auction keeps it so the consent can be taken back. */
    fun showOptIn(
        instanceEnabled: Boolean,
        currency: String,
        status: AuctionStatus,
        endsAt: Instant,
        now: Instant,
        alreadyOptedIn: Boolean = false,
    ): Boolean {
        if (currency !in AuctionCurrencies.SUPPORTED) return false
        if (status != AuctionStatus.OPEN || endsAt <= now) return false
        return instanceEnabled || alreadyOptedIn
    }

    /** What a partner would receive for these inputs, or `null` when the auction cannot be projected (LTR, a title that cannot be shown, a price outside the range). */
    fun previewTeaser(
        title: String,
        currency: String,
        priceMinor: Long,
        hasBids: Boolean,
        buyNowMinor: Long?,
        offersEnabled: Boolean,
        endsAt: Instant,
        now: Instant = Clock.System.now(),
    ): AuctionTeaser? =
        AuctionTeaserProjection.project(
            teaserId = AuctionTeaserProjection.PREVIEW_ID,
            revision = 1,
            localTitle = title,
            currency = currency,
            priceKind = if (hasBids) AuctionTeaserPriceKind.CURRENT else AuctionTeaserPriceKind.START,
            priceMinor = priceMinor,
            buyNowMinor = buyNowMinor,
            offersEnabled = offersEnabled,
            fiatEnabled = true,
            endsAt = endsAt,
            priceAsOf = minOf(now, endsAt),
        )

    fun previewText(teaser: AuctionTeaser?): String {
        if (teaser == null) return titleRefusalText()
        val priceLabel = if (teaser.priceKind == AuctionTeaserPriceKind.CURRENT) tr("Aktueller Preis") else tr("Startpreis")
        val parts =
            mutableListOf(
                gettext(
                    "So sehen Partner Ihre Auktion: %1 · %2 %3 · endet %4",
                    teaser.title,
                    priceLabel,
                    formatAuctionAmount(teaser.currency, null, teaser.priceMinor),
                    formatSystemDateTime(teaser.endsAt.toLocalDateTime(TimeZone.UTC)),
                ),
            )
        teaser.buyNowMinor?.let { parts += gettext("Sofortkauf %1", formatAuctionAmount(teaser.currency, null, it)) }
        if (teaser.offersPossible) parts += tr("Preisvorschlag möglich")
        return parts.joinToString(" · ")
    }

    /** The two sentences under the box: what leaves the server and what never does. The price refresh interval comes from the shared limit. */
    fun explanationText(): String =
        gettext(
            "Partner erhalten Titel, Preis, Ende und – falls vorhanden – den Sofortkaufpreis. " +
                "Der Preis wird höchstens alle %1 Minuten aktualisiert; ein Preissprung lässt erkennen, ob geboten wurde.",
            AuctionFederationLimits.PRICE_REFRESH_MINUTES,
        )

    fun neverSharedText(): String =
        tr("Bieten und Kaufen ist nur bei uns möglich. Verkäufer, Bieter, Gebote und Zahlungsangaben werden nie weitergegeben.")

    fun titleRefusalText(): String = tr("Der Titel dieser Auktion kann Partnern nicht gezeigt werden (leer, Link oder Sonderzeichen).")

    /** The fixed text of each typed refusal of the consent call, or `null` for any other failure (the generic handling applies). */
    fun refusalText(e: Throwable): String? =
        when (e) {
            is AuctionFederationEndsTooSoonException ->
                gettext(
                    "Diese Auktion endet in weniger als %1 Stunden und kann nicht mehr geteilt werden.",
                    AuctionFederationLimits.MIN_REMAINING_FOR_CONSENT_HOURS,
                )
            is AuctionFederationShareLimitException ->
                gettext(
                    "Sie teilen bereits %1 Auktionen. Weitere können nicht geteilt werden.",
                    AuctionFederationLimits.MAX_SHARED_PER_SELLER,
                )
            is AuctionFederationUnavailableException -> tr("Das Teilen von Auktionen mit Partnerorganisationen ist derzeit nicht möglich.")
            is AuctionFederationTitleException -> titleRefusalText()
            is AuctionFederationNotAllowedException -> tr("Diese Auktion kann nicht mit Partnern geteilt werden.")
            else -> null
        }

    /** A new auction starts without consent; an existing one with its stored value. */
    fun initialOptIn(dto: AuctionDto?): Boolean = dto?.federationOptIn ?: false

    /** The withdrawal hint shows while an already shared auction is being unticked: the one moment the seller is about to take it back. */
    fun withdrawalHintVisible(
        initial: Boolean,
        checked: Boolean,
    ): Boolean = initial && !checked

    /** The consent call is made only when the choice changed. */
    fun mustCallSetOptIn(
        initial: Boolean,
        wanted: Boolean,
    ): Boolean = initial != wanted

    /** The badge "Für Partner freigegeben" on the seller's own card: only while the auction really is shared (open and not over). */
    fun badgeVisible(
        dto: AuctionDto,
        now: Instant,
    ): Boolean = dto.federationOptIn && dto.status == AuctionStatus.OPEN && dto.endsAt.toInstant(TimeZone.UTC) > now

    /** The text shown inline under the box after a refusal of the create flow: the auction exists, it is just not shared. */
    fun savedButNotSharedText(reason: String): String =
        gettext("Die Auktion wurde gespeichert, aber nicht für Partner freigegeben. %1", reason)
}

/** The badge text on the seller's own card (the same words and look as the carpool badge, no new icon or colour). */
internal fun auctionFederationBadgeText(): String = carpoolFederationBadgeText()

/**
 * The checkbox with its preview, the explanation, the reach and an inline refusal text. [preview] computes the teaser of the CURRENT inputs;
 * [onChoice] (card mode) makes the consent call right away and returns `true` when it was accepted, a refusal is shown inline (never as a toast)
 * and the box jumps back.
 */
internal class AuctionFederationConsentBox(
    parent: SimplePanel,
    private val initial: Boolean,
    private val preview: () -> AuctionTeaser?,
    private val onChoice: (suspend (Boolean) -> Throwable?)? = null,
) {
    private val host = parent.vPanel(spacing = 4)
    private val reachBox = host.vPanel(spacing = 2) { addCssClass("small") }
    private val box: CheckBox =
        host.checkBox(value = initial, label = tr("Diese Auktion als Kurzhinweis an Partnerorganisationen weitergeben"))
    private val previewLine = host.div("") { addCssClasses("text-muted small") }
    private val explanation = host.div(AuctionFederationUiLogic.explanationText()) { addCssClasses("text-muted small") }
    private val neverShared = host.div(AuctionFederationUiLogic.neverSharedText()) { addCssClasses("text-muted small") }

    // Neutral on purpose (text-muted, no warning colour): taking a consent back is a normal thing to do.
    private val withdrawalHint =
        host.div(
            tr(
                "Partner erhalten beim nächsten Abruf eine Rückzugsmeldung. " +
                    "Bereits übertragene Hinweise können wir dort nicht selbst löschen.",
            ),
        ) { addCssClasses("text-muted small") }
    private val refusalLine =
        host.div("") {
            addCssClasses("text-danger small")
            setAttribute("role", "alert")
        }

    private var available = false
    private var reachRequested = false
    private var busy = false

    /** True while the box is ticked and visible. */
    val wanted: Boolean get() = box.value == true

    /** Shows the inline refusal and puts the box back to "off" (the consent was not given). */
    fun showRefusal(text: String) {
        box.value = false
        refusalLine.content = text
        refusalLine.show()
        sync()
    }

    fun clearRefusal() {
        refusalLine.content = ""
        refusalLine.hide()
    }

    /** Re-evaluates the preview, the hint and whether the box may be ticked. Called when an input of the form changed. */
    fun sync() {
        val teaser = preview()
        previewLine.content = sanitizeUntrustedI18nText(AuctionFederationUiLogic.previewText(teaser))
        box.disabled = busy || (teaser == null && box.value != true) || (!available && !initial)
        if (AuctionFederationUiLogic.withdrawalHintVisible(
                initial = initial,
                checked = box.value == true,
            )
        ) {
            withdrawalHint.show()
        } else {
            withdrawalHint.hide()
        }
        if (available || initial) loadReachOnce()
    }

    /** Tells the box whether the exchange is on (read once by the caller). A shared auction keeps the box even when it is off, to take the consent back. */
    fun setAvailable(value: Boolean) {
        available = value
        if (available || initial) host.show() else host.hide()
        sync()
    }

    private fun loadReachOnce() {
        if (reachRequested) return
        reachRequested = true
        AppScope.launch {
            val reach = loadAuctionFederationReach()
            if (reach == null) renderAuctionReachFailure(reachBox) else renderAuctionReach(reachBox, reach)
        }
    }

    init {
        host.hide()
        withdrawalHint.hide()
        refusalLine.hide()
        box.onClick {
            clearRefusal()
            val choice = box.value == true
            val handler = onChoice
            if (handler != null) {
                busy = true
                box.disabled = true
                AppScope.launch {
                    val failure = handler(choice)
                    busy = false
                    if (failure != null) {
                        val text = AuctionFederationUiLogic.refusalText(failure)
                        if (text != null) showRefusal(text) else box.value = !choice
                    }
                    sync()
                }
            }
            sync()
        }
    }
}

/** The consent sentence, who can fetch the hint and how far it can travel. Names are untrusted text: each in its own span, never a gettext argument. */
internal fun renderAuctionReach(
    into: SimplePanel,
    reach: AuctionFederationReachDto,
) {
    into.removeAll()
    into.div(tr("Ihr Hinweis verlässt unseren Server nur mit Ihrer Zustimmung."))
    into.div(PartnerCarpoolHintsUiLogic.recipientsSentence(n = reach.partners.size, truncated = reach.partnersTruncated))
    if (reach.enabled && reach.partners.isNotEmpty()) {
        into.div(PartnerCarpoolHintsUiLogic.reachSentence(reach.maxHops))
        val collapsed = PartnerCarpoolHintsUiLogic.collapsed(reach.partners.size)
        val toggle =
            if (collapsed) {
                newActionButton(ActionIcon.VIEW, tr("Partnerorganisationen anzeigen"), style = ButtonStyle.LINK, small = true).also {
                    it.addCssClasses("p-0 text-start")
                    it.setAttribute("aria-expanded", "false")
                    into.add(it)
                }
            } else {
                null
            }
        val list = into.tag(TAG.UL, className = "mb-0")
        reach.partners.forEach { label -> list.tag(TAG.LI).untrustedSpan(label) }
        if (toggle != null) {
            list.hide()
            toggle.onClick {
                val open = !list.visible
                if (open) list.show() else list.hide()
                toggle.setAttribute("aria-expanded", open.toString())
            }
        }
    }
}

internal fun renderAuctionReachFailure(into: SimplePanel) {
    into.removeAll()
    into.div(tr("Ihr Hinweis verlässt unseren Server nur mit Ihrer Zustimmung."))
    into.div(tr("Empfänger konnten nicht geladen werden."))
}

private const val CACHE_TTL_MS = 30_000.0
private var availabilityCache: Pair<Double, Boolean>? = null
private var reachCache: Pair<Double, AuctionFederationReachDto>? = null

/**
 * The availability for members (the exchange is on, Euro auctions are on, a key exists); a failure means "not available". Kept for 30 seconds, so a
 * list of the seller's own cards asks once, not once per card.
 */
internal suspend fun loadAuctionFederationAvailable(): Boolean {
    val cached = availabilityCache
    if (cached != null && kotlin.js.Date.now() - cached.first < CACHE_TTL_MS) return cached.second
    val value =
        try {
            rpcService<IAuctionService>().getAuctionFederationAvailability().enabled
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return false
        }
    availabilityCache = kotlin.js.Date.now() to value
    return value
}

/** The reach of the hints (who can fetch them), kept for 30 seconds like [loadAuctionFederationAvailable]; `null` when it could not be loaded. */
private suspend fun loadAuctionFederationReach(): AuctionFederationReachDto? {
    val cached = reachCache
    if (cached != null && kotlin.js.Date.now() - cached.first < CACHE_TTL_MS) return cached.second
    val value =
        try {
            rpcService<IAuctionService>().getAuctionFederationReach()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return null
        }
    reachCache = kotlin.js.Date.now() to value
    return value
}

/**
 * The consent box on the seller's own card of an open Euro auction: the badge when shared, and the box (with the immediate consent call) while the
 * exchange is on or the auction is already shared.
 */
internal fun renderSellerFederationPanel(
    card: SimplePanel,
    auction: AuctionDto,
    onChanged: () -> Unit,
) {
    val now = Clock.System.now()
    val endsAt = auction.endsAt.toInstant(TimeZone.UTC)
    if (AuctionFederationUiLogic.badgeVisible(auction, now)) {
        card.typeBadge(auctionFederationBadgeText(), "info")
    }
    val consentBox =
        AuctionFederationConsentBox(
            parent = card,
            initial = AuctionFederationUiLogic.initialOptIn(auction),
            preview = {
                AuctionFederationUiLogic.previewTeaser(
                    title = auction.title,
                    currency = auction.currency,
                    priceMinor = auction.currentPriceMinor ?: auction.startingPriceMinor ?: 0L,
                    hasBids = auction.bidCount > 0,
                    buyNowMinor = auction.buyNowPriceMinor,
                    offersEnabled = auction.offersEnabled,
                    endsAt = endsAt,
                )
            },
            onChoice = { wanted ->
                var failure: Throwable? = null
                val result =
                    try {
                        rpcService<IAuctionService>().setAuctionFederationOptIn(auction.id, wanted)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        failure = e
                        null
                    }
                if (result != null) {
                    onChanged()
                } else if (failure != null && AuctionFederationUiLogic.refusalText(failure) == null) {
                    // not a typed refusal of this feature: the generic handling (session, forbidden, ...) tells the person
                    guarded { throw failure }
                }
                failure
            },
        )
    AppScope.launch {
        val enabled = loadAuctionFederationAvailable()
        val shown =
            AuctionFederationUiLogic.showOptIn(
                instanceEnabled = enabled,
                currency = auction.currency,
                status = auction.effectiveStatus,
                endsAt = endsAt,
                now = Clock.System.now(),
                alreadyOptedIn = auction.federationOptIn,
            )
        consentBox.setAvailable(shown)
    }
}
