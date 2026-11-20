package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.CarpoolFederationLimits
import network.lapis.cloud.shared.domain.CarpoolFederationSettingsDto
import network.lapis.cloud.shared.domain.CarpoolPullState
import network.lapis.cloud.shared.domain.CarpoolPullStatus
import network.lapis.cloud.shared.domain.CarpoolReceivedRejectReason
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

    // ── V1.9.311 "Mitfahrzentrale-Austausch" ─────────────────────────────────────

    /** The reach choice only matters while the exchange is on. */
    fun hopSelectVisible(enabled: Boolean): Boolean = enabled

    /** The per-partner box "Fahrten teilen" is offered only while the exchange is on. */
    fun partnerSharingVisible(enabled: Boolean): Boolean = enabled

    /** The preselected reach: the default of the server (two levels) when nothing valid is stored. */
    fun hopSelection(stored: Int): Int =
        if (stored in
            CarpoolFederationLimits.MIN_HOPS..CarpoolFederationLimits.MAX_HOPS
        ) {
            stored
        } else {
            CarpoolFederationLimits.DEFAULT_HOPS
        }

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

    /**
     * V1.9.312: the carpool teaser feed lines of a partner row; empty unless the exchange is on AND this partner has carpool sharing on. A partner
     * without a pinned key cannot fetch at all (the line says why); otherwise how often it fetched, when last and how many items the last answer held.
     */
    fun carpoolFeedLines(
        partner: PartnerServerDto,
        exchangeOn: Boolean,
    ): List<String> {
        val lines = mutableListOf<String>()
        // V1.9.313: shown even when the partner's switch is off (that is exactly when withdrawals scoped to it are reported).
        if (partner.carpoolWithdrawalsScoped > 0) {
            lines += gettext("Rückzugsmeldungen aktiv: %1", partner.carpoolWithdrawalsScoped)
        }
        if (!partnerSharingVisible(exchangeOn) || !partner.carpoolSharing) return lines
        if (!partner.carpoolKeyPinned) {
            lines += gettext("Kein Signaturschlüssel des Partners bekannt (der Partner muss Version 1.9.312 oder neuer nutzen)")
        }
        val at = partner.carpoolFeedServedAt
        lines +=
            if (at == null) {
                gettext("Kurzhinweise: noch nie abgerufen")
            } else {
                gettext(
                    "Kurzhinweise abgerufen: %1 (zuletzt %2, %3 Einträge)",
                    partner.carpoolFeedServedCount,
                    formatSystemDateTime(at),
                    partner.carpoolFeedLastItemCount ?: 0,
                )
            }
        // V1.9.314 (wave 4): the pull of THIS server towards the partner and what it holds from the partner.
        val pullAt = partner.carpoolPullAt
        if (pullAt != null) {
            val error = partner.carpoolPullError
            lines +=
                if (partner.carpoolPullStatus == CarpoolPullStatus.FAILED && error != null) {
                    gettext("Abruf der Kurzhinweise fehlgeschlagen: %1", PartnerServersTexts.carpoolPullError(error))
                } else {
                    gettext(
                        "Kurzhinweise zuletzt abgerufen: %1 (%2 übernommen, %3 abgelehnt)",
                        formatSystemDateTime(pullAt),
                        partner.carpoolPullLastAccepted ?: 0,
                        partner.carpoolPullLastRejected ?: 0,
                    )
                }
        }
        val received = partner.carpoolReceivedHints
        if (received > 0) lines += gettext("Empfangene Kurzhinweise von diesem Partner: %1", received)
        // V1.9.318 (wave 6): the operation of the pull. Times and counts only; the error text above stays the code text, never a raw message.
        partner.carpoolPullOkAt?.let { lines += gettext("Letzter Erfolg: %1", formatSystemDateTime(it)) }
        partner.carpoolPullNextAt?.let { lines += gettext("Nächster Versuch: %1", formatSystemDateTime(it)) }
        if (partner.carpoolReceivedBlocks > 0) lines += gettext("Sperren aktiv: %1", partner.carpoolReceivedBlocks)
        return lines
    }

    /** V1.9.318: the word of the pull state (the badge ALWAYS carries the word; the colour only adds to it). */
    fun pullStateLabel(state: CarpoolPullState): String =
        when (state) {
            CarpoolPullState.READY -> gettext("Bereit")
            CarpoolPullState.BACKING_OFF -> gettext("Wartet (Back-off)")
            CarpoolPullState.PAUSED -> gettext("Pausiert")
        }

    /** The Bootstrap badge classes of a state. */
    fun pullStateBadgeClass(state: CarpoolPullState): String =
        when (state) {
            CarpoolPullState.READY -> "bg-success"
            CarpoolPullState.BACKING_OFF -> "bg-warning text-dark"
            CarpoolPullState.PAUSED -> "bg-secondary"
        }

    /** The state badge of a partner row; `null` unless the exchange is on, the partner shares and the server reports a state. */
    fun pullStateOf(
        partner: PartnerServerDto,
        exchangeOn: Boolean,
    ): CarpoolPullState? = if (partnerSharingVisible(exchangeOn) && partner.carpoolSharing) partner.carpoolPullState else null

    /** V1.9.318: the sorted cause lines ("Ablehnungsgründe"), in the FIXED order of [CarpoolReceivedRejectReason]; an unknown key is ignored. */
    fun rejectReasonLines(settings: CarpoolFederationSettingsDto): List<String> =
        CarpoolReceivedRejectReason.entries.map { reason ->
            val count = settings.carpoolReceivedRejectedByReason[reason.name] ?: 0L
            when (reason) {
                CarpoolReceivedRejectReason.SIGNATURE -> gettext("Signatur ungültig oder fehlt: %1", count)
                CarpoolReceivedRejectReason.UNKNOWN_ORIGIN_KEY -> gettext("Schlüssel des Ursprungs unbekannt: %1", count)
                CarpoolReceivedRejectReason.FORMAT -> gettext("Format ungültig: %1", count)
                CarpoolReceivedRejectReason.HOP_LIMIT -> gettext("Weitergabegrenze überschritten: %1", count)
                CarpoolReceivedRejectReason.EXPIRED -> gettext("Abgelaufen: %1", count)
                CarpoolReceivedRejectReason.BLOCKED -> gettext("Gesperrt: %1", count)
                CarpoolReceivedRejectReason.CAPACITY -> gettext("Kapazität erreicht: %1", count)
            }
        }

    /** V1.9.312: the notes under the carpool switch: the missing key, and how many requests were refused / limited since the server started. */
    fun carpoolFeedSettingsLines(settings: CarpoolFederationSettingsDto): List<String> {
        val lines = mutableListOf<String>()
        // V1.9.313: BEFORE the early return: after the exchange was switched off the withdrawals are exactly what is still going on.
        if (settings.carpoolWithdrawalsActive > 0) lines += gettext("Rückzugsmeldungen aktiv: %1", settings.carpoolWithdrawalsActive)
        if (!settings.enabled) return lines
        if (!settings.feedKeyAvailable) lines += gettext("Schlüssel für Kurzhinweis-IDs fehlt (LAPIS_SECRET_ENCRYPTION_KEY)")
        if (settings.feedRefusedSinceStart > 0) lines += gettext("Abgewiesene Abrufe seit dem Start: %1", settings.feedRefusedSinceStart)
        if (settings.feedRateLimitedSinceStart > 0) {
            lines += gettext("Gedrosselte Abrufe seit dem Start: %1", settings.feedRateLimitedSinceStart)
        }
        // V1.9.314 (wave 4)
        if (settings.carpoolReceivedHintsTotal > 0) lines += gettext("Empfangene Kurzhinweise: %1", settings.carpoolReceivedHintsTotal)
        if (settings.carpoolReceivedRejectedSinceStart > 0) {
            lines += gettext("Abgelehnte Kurzhinweise seit dem Start: %1", settings.carpoolReceivedRejectedSinceStart)
        }
        if (settings.carpoolReceivedUnknownOriginKeySinceStart > 0) {
            lines +=
                gettext(
                    "Kurzhinweise ohne bekannten Schlüssel des Ursprungs seit dem Start: %1",
                    settings.carpoolReceivedUnknownOriginKeySinceStart,
                )
        }
        // V1.9.318 (wave 6): the operation. Everything counts from the start of the server process (in memory, 0 after a restart).
        settings.serverStartedAt?.let { started ->
            lines +=
                gettext(
                    "Seit dem Serverstart am %1: %2 ausgeliefert, %3 unverändert, %4 gedrosselt, %5 abgewiesen",
                    formatSystemDateTime(started),
                    settings.feedServedSinceStart,
                    settings.feedNotModifiedSinceStart,
                    settings.feedRateLimitedSinceStart,
                    settings.feedRefusedSinceStart,
                )
        }
        if (settings.carpoolReceivedStoredRows > 0) {
            lines +=
                gettext(
                    "Gespeichert: %1 (davon abgelaufen, noch nicht gelöscht: %2)",
                    settings.carpoolReceivedStoredRows,
                    (settings.carpoolReceivedStoredRows - settings.carpoolReceivedHintsTotal).coerceAtLeast(0),
                )
        }
        if (settings.carpoolReceivedAcceptedSinceStart > 0) {
            lines += gettext("Übernommen seit dem Start: %1", settings.carpoolReceivedAcceptedSinceStart)
        }
        if (settings.carpoolReceivedExpiredDeletedSinceStart > 0) {
            lines += gettext("Gelöscht nach Ablauf seit dem Start: %1", settings.carpoolReceivedExpiredDeletedSinceStart)
        }
        if (settings.carpoolPullBudgetDeferredSinceStart > 0) {
            lines += gettext("Aufgeschoben (Abrufbudget) seit dem Start: %1", settings.carpoolPullBudgetDeferredSinceStart)
        }
        if (settings.carpoolRelayTombstonesSkippedSinceStart > 0) {
            lines +=
                gettext(
                    "Nicht geschriebene Weitergabe-Rückzüge seit dem Start: %1",
                    settings.carpoolRelayTombstonesSkippedSinceStart,
                )
        }
        return lines
    }

    /** What a discovered server shows as its state: the error words, else "Abgerufen", else the waiting words. */
    fun discoveredStatus(server: DiscoveredPartnerServerDto): String =
        server.lastError?.let { PartnerServersTexts.fetchError(it) }
            ?: if (server.lastStatus == PartnerFetchStatus.OK) gettext("Abgerufen") else gettext("Wartet auf den ersten Abruf")
}
