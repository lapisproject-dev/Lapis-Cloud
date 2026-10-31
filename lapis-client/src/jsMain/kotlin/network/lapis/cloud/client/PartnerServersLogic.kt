package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.DiscoveredPartnerServerDto
import network.lapis.cloud.shared.domain.PartnerFetchStatus
import network.lapis.cloud.shared.domain.PartnerRecursionSettingsDto
import network.lapis.cloud.shared.domain.PartnerRecursionSummaryDto
import network.lapis.cloud.shared.domain.PartnerServerDto

/** The four states of a partner row; the [symbol] is shown WITH the text (never colour alone) and is hidden from screen readers. */
internal enum class PartnerStatusKind(
    val symbol: String,
) {
    FETCHED("●"),
    WAITING("◐"),
    PAUSED("○"),
    BLOCKED("⊘"),
}

/** V1.9.244 -- the DOM-free decisions of the partner page. */
internal object PartnerServersLogic {
    /** Blocked wins over paused, paused over the fetch result; an error or a never-fetched partner is "waiting" (the back-off is running). */
    fun statusKind(partner: PartnerServerDto): PartnerStatusKind =
        when {
            partner.blocked -> PartnerStatusKind.BLOCKED
            !partner.active -> PartnerStatusKind.PAUSED
            partner.status == PartnerFetchStatus.OK -> PartnerStatusKind.FETCHED
            else -> PartnerStatusKind.WAITING
        }

    fun statusText(partner: PartnerServerDto): String =
        when (statusKind(partner)) {
            PartnerStatusKind.BLOCKED -> gettext("Gesperrt")
            PartnerStatusKind.PAUSED -> gettext("Pausiert")
            PartnerStatusKind.FETCHED -> gettext("Abgerufen")
            PartnerStatusKind.WAITING ->
                partner.nextFetchAt?.let { gettext("Wartet bis %1", formatSystemTime(it)) } ?: gettext("Wartet auf den ersten Abruf")
        }

    /** The note under a blocked row. */
    fun blockedNote(): String = gettext("Gesperrt – wird nicht abgerufen und nicht angezeigt")

    /** The name a row shows first: the admin's own label, else the name the partner announces, else the host. Foreign text: set via `untrusted*`. */
    fun title(partner: PartnerServerDto): String = partner.displayName ?: partner.remoteName ?: partner.host

    /** `true` when the host line adds information to the title. */
    fun showsHostLine(partner: PartnerServerDto): Boolean = title(partner) != partner.host

    /** Whether the "Prüfen und speichern" button may be pressed: an address was typed. */
    fun canProbe(address: String): Boolean = address.isNotBlank()

    /** The blocklist details element is closed while there is nothing in it. */
    fun blocklistOpen(count: Int): Boolean = count > 0

    // ── V1.9.245 "Partner von Partnern" ─────────────────────────────────────────

    /** The depth choice only matters while the recursion is on. */
    fun depthSelectEnabled(enabled: Boolean): Boolean = enabled

    /** The chain a discovered server was reached through, nearest first: "über a.beispiel.de → b.beispiel.de". Foreign text: set via `untrusted*`. */
    fun chainText(via: List<String>): String = gettext("über %1", via.joinToString(" → "))

    /** "Stufe 2" for hop 2 and so on. */
    fun levelText(hops: Int): String = gettext("Stufe %1", hops)

    /** The one-line state of the last run. */
    fun runStatus(settings: PartnerRecursionSettingsDto): String {
        val summary = settings.summary
        val lastRun = settings.lastRunAt
        if (lastRun == null || summary == null) return gettext("Noch kein Lauf")
        return gettext(
            "Letzter Lauf: %1 · %2 Server · %3 Veranstaltungen",
            formatSystemDateTime(lastRun),
            summary.reachedByHop.sum(),
            summary.eventsByHop.sum(),
        )
    }

    /** The breakdown of the last run: one line per level that was walked, then every non-zero counter of what was skipped. */
    fun summaryLines(summary: PartnerRecursionSummaryDto): List<String> {
        val lines = mutableListOf<String>()
        summary.reachedByHop.forEachIndexed { index, servers ->
            lines += gettext("Stufe %1: %2 Server, %3 Veranstaltungen", index + 2, servers, summary.eventsByHop.getOrElse(index) { 0 })
        }
        if (summary.skippedBlocked > 0) lines += gettext("Sperrliste: %1", summary.skippedBlocked)
        if (summary.skippedHiddenList > 0) lines += gettext("Ausgeblendete Partnerliste: %1", summary.skippedHiddenList)
        if (summary.skippedVisited > 0) lines += gettext("Bereits besucht: %1", summary.skippedVisited)
        if (summary.skippedCap > 0) lines += gettext("Obergrenze erreicht: %1", summary.skippedCap)
        if (summary.serverIdConflicts > 0) lines += gettext("Kennungskonflikt: %1", summary.serverIdConflicts)
        if (summary.errors > 0) lines += gettext("Fehler: %1", summary.errors)
        if (summary.aborted) lines += gettext("Zeitbudget erreicht, Rest im nächsten Lauf")
        return lines
    }

    /** The line in a direct partner's row; `null` while nothing was reached through it. */
    fun viaThisPartner(partner: PartnerServerDto): String? =
        if (partner.discoveredServerCount > 0) {
            gettext("Über diesen Partner: %1 Server, %2 Veranstaltungen", partner.discoveredServerCount, partner.discoveredEventCount)
        } else {
            null
        }

    /** What a discovered server shows as its state: the error words, else "Abgerufen", else the waiting words. */
    fun discoveredStatus(server: DiscoveredPartnerServerDto): String =
        server.lastError?.let { PartnerServersTexts.fetchError(it) }
            ?: if (server.lastStatus == PartnerFetchStatus.OK) gettext("Abgerufen") else gettext("Wartet auf den ersten Abruf")
}
