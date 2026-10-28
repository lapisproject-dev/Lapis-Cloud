package network.lapis.cloud.client

import io.kvision.i18n.gettext
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.DashboardReasonKeys
import network.lapis.cloud.shared.domain.DashboardSignalDto

/**
 * V1.9.224 -- the sentence of a dashboard signal. The server sends a reason key and digits / ISO dates, never text; this is the only place the
 * words are chosen. Every sentence is a whole message with a singular (exactly one object) and a plural form that carries the number as `%1`,
 * so a translation can restructure it ("Идут выборы: %1") instead of inflecting a counted noun. Dates are formatted for the reader.
 */
internal object DashboardSignalTexts {
    private val DIGITS = Regex("""^\d{1,6}$""")
    private val ISO_DATE = Regex("""^\d{4}-\d{2}-\d{2}(T[\d:.]+Z?)?$""")

    /** `true` iff every parameter has the shape the server promises (defence against a server that sends free text). */
    fun paramsAreSafe(signal: DashboardSignalDto): Boolean = signal.params.values.all { DIGITS.matches(it) || ISO_DATE.matches(it) }

    /** The sentence, or `null` for a reason this client does not know (the card is then not drawn) or for unsafe parameters. */
    fun text(signal: DashboardSignalDto): String? {
        if (!paramsAreSafe(signal)) return null
        val many = signal.count > 1
        val n = signal.count.toString()
        val date = signal.params["date"]?.let(::formatSignalDate)
        return when (signal.reasonKey) {
            DashboardReasonKeys.AUCTION_OUTBID ->
                if (many) gettext("Sie wurden in %1 Auktionen überboten", n) else gettext("Sie wurden überboten")
            DashboardReasonKeys.AUCTION_ENDING_SOON ->
                if (many) gettext("Sie führen in %1 Auktionen, die bald enden", n) else gettext("Sie führen, die Auktion endet bald")
            DashboardReasonKeys.AUCTION_OWN_HAS_BIDS -> auctionOwnHasBids(signal, n)
            DashboardReasonKeys.AUCTION_WON ->
                if (many) gettext("Sie haben %1 Auktionen gewonnen", n) else gettext("Sie haben eine Auktion gewonnen")
            DashboardReasonKeys.AUCTION_SOLD ->
                if (many) gettext("%1 Ihrer Auktionen wurden verkauft", n) else gettext("Ihre Auktion wurde verkauft")
            DashboardReasonKeys.TENDER_DEADLINE_SOON ->
                if (many) {
                    gettext("%1 Ausschreibungen laufen bald ab, Sie haben noch nicht geboten", n)
                } else {
                    gettext("Eine Ausschreibung läuft bald ab, Sie haben noch nicht geboten")
                }
            DashboardReasonKeys.TENDER_OWN_BID_DECIDED ->
                if (many) gettext("Über %1 Ihrer Angebote wurde entschieden", n) else gettext("Über Ihr Angebot wurde entschieden")
            DashboardReasonKeys.ELECTION_NOT_VOTED ->
                if (many) {
                    gettext("%1 Wahlen laufen, Sie haben noch nicht abgestimmt", n)
                } else {
                    gettext("Eine Wahl läuft, Sie haben noch nicht abgestimmt")
                }
            DashboardReasonKeys.CONSENSUS_NOT_VOTED ->
                if (many) {
                    gettext("%1 Konsensierungen laufen, Sie haben noch nicht bewertet", n)
                } else {
                    gettext("Eine Konsensierung läuft, Sie haben noch nicht bewertet")
                }
            DashboardReasonKeys.SURVEY_NOT_ANSWERED -> surveyNotAnswered(many, n, date)
            DashboardReasonKeys.MOTION_VOTE_OPEN ->
                if (many) {
                    gettext("%1 Abstimmungen laufen, Sie haben noch nicht abgestimmt", n)
                } else {
                    gettext("Eine Abstimmung läuft, Sie haben noch nicht abgestimmt")
                }
            DashboardReasonKeys.MEETING_UPCOMING -> meetingUpcoming(many, n, date)
            DashboardReasonKeys.MEETING_PROTOCOL_TASK_OVERDUE ->
                if (many) {
                    gettext("%1 Aufgaben aus Protokollen sind überfällig", n)
                } else {
                    gettext("Eine Aufgabe aus Protokollen ist überfällig")
                }
            DashboardReasonKeys.MEETING_PROTOCOL_TASK_OPEN ->
                if (many) gettext("%1 offene Aufgaben aus Protokollen", n) else gettext("Eine offene Aufgabe aus Protokollen")
            DashboardReasonKeys.CROWDFUNDING_OWN_DECIDED ->
                if (many) {
                    gettext("Über %1 Ihrer Crowdfunding-Projekte wurde entschieden", n)
                } else {
                    gettext("Über Ihr Crowdfunding-Projekt wurde entschieden")
                }
            DashboardReasonKeys.EVENTS_NEXT_REGISTERED -> date?.let { gettext("Ihre nächste Veranstaltung ist am %1", it) }
            DashboardReasonKeys.CONTRIBUTIONS_OVERDUE ->
                if (many) gettext("%1 Beiträge sind überfällig", n) else gettext("Ein Beitrag ist überfällig")
            DashboardReasonKeys.CONTRIBUTIONS_OPEN -> contributionsOpen(many, n, date)
            DashboardReasonKeys.TRAVEL_STATUS_CHANGED ->
                if (many) {
                    gettext("%1 Ihrer Reisekostenabrechnungen wurden bearbeitet", n)
                } else {
                    gettext("Eine Reisekostenabrechnung wurde bearbeitet")
                }
            DashboardReasonKeys.AI_DRAFTS_WAITING ->
                if (many) gettext("%1 KI-Entwürfe warten auf Ihre Freigabe", n) else gettext("Ein KI-Entwurf wartet auf Ihre Freigabe")
            DashboardReasonKeys.APPLICATIONS_OPEN ->
                if (many) {
                    gettext("%1 Beitrittsanträge warten auf Entscheidung", n)
                } else {
                    gettext("Ein Beitrittsantrag wartet auf Entscheidung")
                }
            DashboardReasonKeys.TREASURY_OVERDUE_NOT_IN_DUNNING ->
                if (many) {
                    gettext("%1 überfällige Beiträge sind noch nicht im Mahnwesen", n)
                } else {
                    gettext("Ein überfälliger Beitrag ist noch nicht im Mahnwesen")
                }
            else -> null
        }
    }

    private fun auctionOwnHasBids(
        signal: DashboardSignalDto,
        n: String,
    ): String {
        if (signal.count > 1) return gettext("Auf %1 Ihrer Auktionen wurde geboten", n)
        val bids = signal.params["bids"]
        return if (bids == null || bids == "1") gettext("Ihre Auktion hat ein Gebot") else gettext("Ihre Auktion hat %1 Gebote", bids)
    }

    private fun surveyNotAnswered(
        many: Boolean,
        n: String,
        date: String?,
    ): String =
        when {
            many && date != null -> gettext("%1 Umfragen laufen, die erste bis %2, Sie haben noch nicht teilgenommen", n, date)
            many -> gettext("%1 Umfragen laufen, Sie haben noch nicht teilgenommen", n)
            date != null -> gettext("Eine Umfrage läuft bis %1, Sie haben noch nicht teilgenommen", date)
            else -> gettext("Eine Umfrage läuft, Sie haben noch nicht teilgenommen")
        }

    private fun meetingUpcoming(
        many: Boolean,
        n: String,
        date: String?,
    ): String? =
        when {
            many && date != null -> gettext("%1 Sitzungen in den nächsten 7 Tagen, die erste am %2", n, date)
            many -> gettext("%1 Sitzungen in den nächsten 7 Tagen", n)
            date != null -> gettext("Eine Sitzung am %1", date)
            else -> null
        }

    private fun contributionsOpen(
        many: Boolean,
        n: String,
        date: String?,
    ): String? =
        when {
            many && date != null -> gettext("%1 Beiträge sind offen, der erste ist am %2 fällig", n, date)
            many -> gettext("%1 Beiträge sind offen", n)
            date != null -> gettext("Ein Beitrag ist offen und am %1 fällig", date)
            else -> gettext("Ein Beitrag ist offen")
        }

    /** Day and month in the reader's format, with the year when the date is not in the current year. `null` for text that is not a date. */
    fun formatSignalDate(iso: String): String? {
        val date = runCatching { LocalDate.parse(iso.take(DATE_LENGTH)) }.getOrNull() ?: return null
        return if (date.year == organizationToday().year) formatDayMonth(date) else formatDate(date)
    }

    private const val DATE_LENGTH = 10
}

/** [DashboardSignalTexts.formatSignalDate] as a plain function (the shape the tests and the texts use). */
internal fun formatSignalDate(iso: String): String? = DashboardSignalTexts.formatSignalDate(iso)
