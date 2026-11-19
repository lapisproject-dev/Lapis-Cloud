package network.lapis.cloud.client

import io.kvision.form.check.CheckBox
import io.kvision.html.ButtonStyle
import io.kvision.html.TAG
import io.kvision.html.div
import io.kvision.html.tag
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.CarpoolFederationReachDto
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.CarpoolTeaser
import network.lapis.cloud.shared.domain.PartnerCarpoolHintDto
import network.lapis.cloud.shared.domain.PartnerCarpoolHintLimits
import network.lapis.cloud.shared.domain.projectCarpoolTeaser

/*
 * V1.9.311 "Mitfahrzentrale-Foederation" W1 -- the owner's consent box in the trip form. Nothing is transferred in this wave; the box records the
 * consent. The preview below the box is computed by the SAME shared function the server will use for the feed (`projectCarpoolTeaser`): what is
 * shown here is what a partner would receive. This file is the ONLY place in the client that calls it (pinned by CarpoolFederationTripwireTest).
 */

/** The DOM-free decisions of the consent box. */
internal object CarpoolFederationUiLogic {
    /** A date that stands in while the form has none yet; the projection needs one, the postcode areas do not depend on it. */
    private val PLACEHOLDER_DATE = LocalDate(2000, 1, 1)

    /** The box is offered for an OFFER while the exchange is on; a trip that is already shared keeps the box so the consent can be withdrawn. */
    fun showOptIn(
        type: CarpoolPostingType?,
        instanceEnabled: Boolean,
        alreadyOptedIn: Boolean,
    ): Boolean = type == CarpoolPostingType.OFFER && (instanceEnabled || alreadyOptedIn)

    /** What a partner would receive for these inputs, or null when a place carries no postcode. */
    fun preview(
        from: String,
        to: String,
        date: LocalDate?,
    ): CarpoolTeaser? =
        projectCarpoolTeaser(
            fromPlace = from,
            toPlace = to,
            departureDate = date ?: PLACEHOLDER_DATE,
            teaserId = CarpoolTeaser.PREVIEW_ID,
        )

    fun previewText(
        teaser: CarpoolTeaser?,
        date: LocalDate?,
    ): String =
        if (teaser == null) {
            gettext("Start oder Ziel hat keine Postleitzahl – diese Fahrt kann nicht geteilt werden.")
        } else {
            gettext(
                "So sehen Partner Ihre Fahrt: %1 · PLZ-Gebiet %2 → PLZ-Gebiet %3",
                date?.let { formatDate(it) } ?: "–",
                teaser.fromPostcodeArea,
                teaser.toPostcodeArea,
            )
        }

    /** The box can be ticked only while the trip can be shared; an already ticked box can always be unticked (withdrawal). */
    fun checkboxEnabled(
        teaser: CarpoolTeaser?,
        currentlyChecked: Boolean,
    ): Boolean = teaser != null || currentlyChecked

    /** A new and a duplicated trip always start without consent; an edited trip starts with its stored value. */
    fun initialOptIn(prefill: CarpoolFormPrefill?): Boolean = prefill?.editing?.federationOptIn ?: false

    /** V1.9.313: the withdrawal hint shows while an already shared trip is being unticked: the one moment the owner is about to take it back. */
    fun withdrawalHintVisible(
        initial: Boolean,
        checked: Boolean,
    ): Boolean = initial && !checked

    /** The second call is made only when the consent changed. */
    fun mustCallSetOptIn(
        initial: Boolean,
        wanted: Boolean,
    ): Boolean = initial != wanted
}

/**
 * V1.9.317 (wave 5): the DOM-free decisions of the list of received hints and of the reach text in the consent box. German source texts only; every
 * foreign value that reaches the DOM later (host, area) goes through the `untrusted*` helpers, partner labels are never a gettext argument.
 */
internal object PartnerCarpoolHintsUiLogic {
    /** Digits only, at most two: the filter is a postcode-area prefix. */
    fun sanitizeFilter(raw: String): String = raw.filter { it in '0'..'9' }.take(2)

    /** Empty filter = everything; otherwise the start OR the destination begins with the digits. */
    fun matches(
        hint: PartnerCarpoolHintDto,
        filter: String,
    ): Boolean = filter.isEmpty() || hint.fromPostcodeArea.startsWith(filter) || hint.toPostcodeArea.startsWith(filter)

    /** The text for an empty list, or null while there is something to show. [visible] = rows that pass the filter. */
    fun emptyText(
        totalLoaded: Int,
        visible: Int,
        filter: String,
        hasMore: Boolean,
    ): String? =
        when {
            visible > 0 -> null
            totalLoaded == 0 -> gettext("Zurzeit liegen keine Hinweise von Partnerorganisationen vor.")
            hasMore -> gettext("Keine Treffer für PLZ-Gebiet %1 in den geladenen Hinweisen.", filter)
            else -> gettext("Keine Angebote für PLZ-Gebiet %1.", filter)
        }

    fun routeText(hint: PartnerCarpoolHintDto): String =
        gettext("PLZ-Gebiet %1 → PLZ-Gebiet %2", hint.fromPostcodeArea, hint.toPostcodeArea)

    fun originText(host: String): String = gettext("von %1 – Details und Kontakt nur bei dieser Organisation", host)

    /** The client never asks for a page the server would refuse. */
    fun canLoadMore(
        hasMore: Boolean,
        page: Int,
    ): Boolean = hasMore && page < PartnerCarpoolHintLimits.MAX_PAGE

    fun recipientsSentence(
        n: Int,
        truncated: Boolean = false,
    ): String =
        when {
            n <= 0 -> gettext("Derzeit ruft keine Partnerorganisation Hinweise ab.")
            truncated -> gettext("Abrufen können ihn mehr als %1 Partnerorganisationen.", n)
            else -> ngettext("Abrufen kann ihn %1 Partnerorganisation.", "Abrufen können ihn %1 Partnerorganisationen.", n, n)
        }

    /** The pass-on reach in words; an unknown value reads like the most conservative one. */
    fun reachSentence(maxHops: Int): String =
        when (maxHops) {
            2 -> gettext("Er geht an direkte Partner und kann von diesen an deren Partner weitergegeben werden.")
            3 -> gettext("Er geht an direkte Partner und kann über deren Partner noch eine Stufe weiter weitergegeben werden.")
            else -> gettext("Er geht nur an direkte Partner und wird von diesen nicht weitergegeben.")
        }

    /** A long list of names is folded behind a button. */
    fun collapsed(n: Int): Boolean = n >= 5

    /** The reach sentence is shown only while the exchange is on and somebody can fetch the hint. */
    fun showReachSentence(reach: CarpoolFederationReachDto): Boolean = reach.enabled && reach.partners.isNotEmpty()
}

/** The box and its preview inside the trip form. Built BEFORE the form buttons. */
internal class CarpoolFederationOptIn(
    form: LapisForm,
    private val initial: Boolean,
    private val currentType: () -> CarpoolPostingType?,
    private val from: () -> String,
    private val to: () -> String,
    private val date: () -> LocalDate?,
) {
    private val host = form.panel.vPanel(spacing = 4)

    // V1.9.317: what happens to the hint, ABOVE the checkbox so the consent is informed when it is given.
    private val reachBox = host.vPanel(spacing = 2) { addCssClass("small") }
    private var box: CheckBox? = null
    private val consent =
        form.checkField(
            label = tr("Diese Fahrt als Kurzhinweis an Partnerorganisationen weitergeben"),
            value = initial,
            host = host,
            init = { box = it },
        )
    private val previewLine = host.div("") { addCssClasses("text-muted small") }

    // Neutral on purpose (text-muted, no warning colour): taking a consent back is a normal thing to do.
    private val withdrawalHint =
        host.div(
            gettext(
                "Partner erhalten beim nächsten Abruf eine Rückzugsmeldung. " +
                    "Bereits übertragene Hinweise können wir dort nicht selbst löschen.",
            ),
        ) { addCssClasses("text-muted small") }

    private var instanceEnabled = false

    /** True while the box is ticked and visible. */
    val wanted: Boolean get() = visibleNow() && consent.value == "true"

    private fun visibleNow() =
        CarpoolFederationUiLogic.showOptIn(type = currentType(), instanceEnabled = instanceEnabled, alreadyOptedIn = initial)

    /** Re-evaluates visibility, the preview and whether the box may be ticked. Safe to call from every consent's change handler. */
    fun sync() {
        if (visibleNow()) {
            host.show()
            loadReachOnce()
        } else {
            host.hide()
        }
        val date = date()
        val teaser = CarpoolFederationUiLogic.preview(from = from().trim(), to = to().trim(), date = date)
        val text = CarpoolFederationUiLogic.previewText(teaser = teaser, date = date)
        previewLine.content = text
        box?.disabled = !CarpoolFederationUiLogic.checkboxEnabled(teaser = teaser, currentlyChecked = consent.value == "true")
        if (CarpoolFederationUiLogic.withdrawalHintVisible(initial = initial, checked = consent.value == "true")) {
            withdrawalHint.show()
        } else {
            withdrawalHint.hide()
        }
    }

    private var reachRequested = false
    private var readReach: (suspend () -> CarpoolFederationReachDto)? = null

    /**
     * Reads the switch once through [readEnabled] (members cannot call the admin service); a failure keeps the box hidden. The reach ([readReach])
     * is read once, the first time the box is visible: never for a REQUEST that is not shared.
     */
    fun load(
        readEnabled: suspend () -> Boolean,
        readReach: (suspend () -> CarpoolFederationReachDto)? = null,
    ) {
        this.readReach = readReach
        sync()
        AppScope.launch {
            instanceEnabled = runCatching { readEnabled() }.getOrDefault(false)
            sync()
        }
    }

    private fun loadReachOnce() {
        val read = readReach ?: return
        if (reachRequested) return
        reachRequested = true
        AppScope.launch {
            val result = runCatching { read() }
            val reach = result.getOrNull()
            if (reach == null) {
                renderReachFailure(reachBox)
            } else {
                renderReach(reachBox, reach)
            }
        }
    }

    init {
        consent.subscribe { sync() }
        host.hide()
    }
}

/** The badge text on the owner's own card. */
internal fun carpoolFederationBadgeText(): String = gettext("Für Partner freigegeben")

/** The consent sentence, who can fetch the hint and how far it can travel. Names are untrusted text: each in its own span, never a gettext argument. */
internal fun renderReach(
    into: SimplePanel,
    reach: CarpoolFederationReachDto,
) {
    into.removeAll()
    into.div(tr("Ihr Hinweis verlässt unseren Server nur mit Ihrer Zustimmung."))
    into.div(PartnerCarpoolHintsUiLogic.recipientsSentence(n = reach.partners.size, truncated = reach.partnersTruncated))
    if (PartnerCarpoolHintsUiLogic.showReachSentence(reach)) {
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

internal fun renderReachFailure(into: SimplePanel) {
    into.removeAll()
    into.div(tr("Ihr Hinweis verlässt unseren Server nur mit Ihrer Zustimmung."))
    into.div(tr("Empfänger konnten nicht geladen werden."))
}
