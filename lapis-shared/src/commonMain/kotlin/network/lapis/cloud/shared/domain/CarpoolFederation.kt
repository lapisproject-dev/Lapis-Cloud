package network.lapis.cloud.shared.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.Serializable

/*
 * V1.9.311 "Mitfahrzentrale-Foederation" W1 (foundation) -- see docs/architecture/carpool-federation.adoc.
 *
 * Nothing is sent to another server in this wave. This file holds the ONE projection from a trip to the short hint a partner organisation may
 * see, and the ONE eligibility rule. The client preview and (from wave 2) the server feed call the SAME functions, so what the owner sees in the
 * preview is by construction what a partner receives. No Lookbehind and no `\d` here: the code runs on the JVM and in Kotlin/JS alike.
 */

/**
 * The short hint about a trip that a partner organisation may receive: the day and the first two digits of the postcode of the start and of the
 * destination (a rough region of roughly a hundred kilometres across). NOTHING else: no place text, no time, no seats, no notes, no name, no
 * internal id. [teaserId] is a pseudonymous id that is NOT derivable from the trip id by anyone outside this server (wave 2).
 */
@Serializable
data class CarpoolTeaser(
    val teaserId: String,
    val departureDate: LocalDate,
    val fromPostcodeArea: String,
    val toPostcodeArea: String,
) {
    init {
        require(AREA.matches(fromPostcodeArea)) { "invalid from postcode area" }
        require(AREA.matches(toPostcodeArea)) { "invalid to postcode area" }
        require(ID.matches(teaserId)) { "invalid teaser id" }
    }

    companion object {
        private val AREA = Regex("^[0-9]{2}$")
        private val ID = Regex("^[A-Za-z0-9_-]{16,64}$")

        /** A syntactically valid id used ONLY for the local preview in the client. It is never sent anywhere. */
        const val PREVIEW_ID = "local-preview-only"
    }
}

object CarpoolFederationLimits {
    const val MIN_HOPS = 1
    const val DEFAULT_HOPS = 2
    const val MAX_HOPS = 3

    /** A trip is shared at most this many days before departure. */
    const val MAX_DAYS_AHEAD = 90
}

fun clampHopLimit(n: Int): Int = n.coerceIn(CarpoolFederationLimits.MIN_HOPS, CarpoolFederationLimits.MAX_HOPS)

/**
 * The first two digits of the postcode in [place], or null. A postcode is the FIRST run of exactly five ASCII digits `0-9` (a longer or shorter
 * run is no postcode; `D-` / `DE-` stand before the digits and do not matter). The value must lie in 01001..99998. If the FIRST such run is out of
 * range the result is null -- the next run is NOT tried. Fullwidth or other Unicode digits never match.
 */
fun extractPostcodeArea(place: String): String? {
    var i = 0
    while (i < place.length) {
        if (place[i] in '0'..'9') {
            var j = i
            while (j < place.length && place[j] in '0'..'9') j++
            if (j - i == 5) {
                val value = place.substring(i, j).toInt()
                return if (value in 1001..99998) place.substring(i, i + 2) else null
            }
            i = j
        } else {
            i++
        }
    }
    return null
}

/**
 * THE projection. It takes only the three source fields on purpose -- no row, no DTO -- so it structurally cannot see anything else. Null when
 * the start or the destination carries no postcode (such a trip cannot be shared).
 */
fun projectCarpoolTeaser(
    fromPlace: String,
    toPlace: String,
    departureDate: LocalDate,
    teaserId: String,
): CarpoolTeaser? {
    val from = extractPostcodeArea(fromPlace) ?: return null
    val to = extractPostcodeArea(toPlace) ?: return null
    return CarpoolTeaser(teaserId = teaserId, departureDate = departureDate, fromPostcodeArea = from, toPostcodeArea = to)
}

/**
 * Everything the eligibility decision needs. "The trip still exists" is implied: only existing rows are loaded. There is no event link and no
 * cancelled status on `carpool_posting`, so nothing of that kind is checked.
 */
data class CarpoolTeaserEligibility(
    val type: CarpoolPostingType,
    val optIn: Boolean,
    val instanceEnabled: Boolean,
    val partnerSharing: Boolean,
    val partnerActive: Boolean,
    val partnerBlocked: Boolean,
    val authorActive: Boolean,
    val departureDate: LocalDate,
    val seatsOffered: Int?,
    val confirmedCount: Int,
    val fromPlace: String,
    val toPlace: String,
)

/** True only if EVERY condition holds. [today] is the organisation's day (not a fixed zone). */
fun isCarpoolTeaserEligible(
    e: CarpoolTeaserEligibility,
    today: LocalDate,
): Boolean {
    if (e.type != CarpoolPostingType.OFFER || !e.optIn || !e.instanceEnabled) return false
    if (!e.partnerSharing || !e.partnerActive || e.partnerBlocked) return false
    if (!e.authorActive) return false
    if (e.departureDate < today || e.departureDate > today.plus(CarpoolFederationLimits.MAX_DAYS_AHEAD, DateTimeUnit.DAY)) return false
    val seats = e.seatsOffered ?: return false
    if (e.confirmedCount >= seats) return false
    return projectCarpoolTeaser(
        fromPlace = e.fromPlace,
        toPlace = e.toPlace,
        departureDate = e.departureDate,
        teaserId = CarpoolTeaser.PREVIEW_ID,
    ) != null
}

/** Settings of the exchange (admin view). */
@Serializable
data class CarpoolFederationSettingsDto(
    val enabled: Boolean,
    val hopLimit: Int,
    /** V1.9.312: the key that derives the pseudonymous teaser ids is configured (`LAPIS_SECRET_ENCRYPTION_KEY`); without it the teaser feed refuses every request. */
    val feedKeyAvailable: Boolean = false,
    /** V1.9.312: requests the teaser feed refused since the server started (all causes together; never says which). */
    val feedRefusedSinceStart: Long = 0,
    /** V1.9.312: requests the teaser feed answered with 429 since the server started. */
    val feedRateLimitedSinceStart: Long = 0,
)

/** What a member needs to know: is the exchange switched on at all (the owner dialog shows the consent box only then). */
@Serializable
data class CarpoolFederationAvailabilityDto(
    val enabled: Boolean,
)

/** After-snapshot of an [AuditEntityType.CARPOOL_FEDERATION_OPT_IN] entry: the new state only -- no place, no date, no text. */
@Serializable
data class CarpoolFederationOptInAuditSnapshot(
    val optIn: Boolean,
)

/** After-snapshot of an [AuditEntityType.CARPOOL_FEDERATION_FEED] entry: how many hints were handed out and whether the list was complete -- nothing else. */
@Serializable
data class CarpoolFederationFeedAuditSnapshot(
    val itemCount: Int,
    val complete: Boolean,
)
