package network.lapis.cloud.client

import io.kvision.form.check.CheckBox
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.CarpoolTeaser
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
        if (visibleNow()) host.show() else host.hide()
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

    /** Reads the switch once through [readEnabled] (members cannot call the admin service); a failure keeps the box hidden. */
    fun load(readEnabled: suspend () -> Boolean) {
        sync()
        AppScope.launch {
            instanceEnabled = runCatching { readEnabled() }.getOrDefault(false)
            sync()
        }
    }

    init {
        consent.subscribe { sync() }
        host.hide()
    }
}

/** The badge text on the owner's own card. */
internal fun carpoolFederationBadgeText(): String = gettext("Für Partner freigegeben")
