package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/*
 * V1.9.144 -- the optional member finder ("Mitglieder-Finder"). Members who explicitly consent can send a neutral request to ONE randomly
 * chosen other consenting member of the same region (the first two digits of the German postal code). Names become visible only after both
 * sides agreed. Party membership is a special category of data (Art. 9 GDPR and comparable rules), so the design deliberately stores and
 * shows as little as possible: see docs/features/member-finder.adoc and docs/architecture/member-finder.adoc.
 */

/** The smallest number of consenting members a region needs before it is visible at all. FIXED: never configurable, never below 5. */
const val MEMBER_FINDER_MIN_REGION_SIZE: Int = 5

/** Version of the operator notice an ADMIN acknowledges before switching the finder on. Bump on ANY change of `MemberFinderNotice.TEXT_DE`. */
const val MEMBER_FINDER_NOTICE_VERSION: String = "1"

/** What the member is interested in. */
@Serializable
enum class MemberFinderInterest { GROUP_FOUNDING, MEETUP, BOTH }

/** The purpose of one request. */
@Serializable
enum class MemberFinderPurpose { GROUP_FOUNDING, MEETUP }

/** The only granularity at which a group size is ever shown. Exact numbers are never shown. */
@Serializable
enum class MemberFinderBand { B5_9, B10_19, B20_PLUS }

/** The ONLY states a sender sees of an own request. A declined, a phantom and an open request are all [PENDING] until they end. */
@Serializable
enum class MemberFinderOutgoingState { PENDING, ENDED, WITHDRAWN, CONNECTED }

/** The fixed limits of the finder. None of them is configurable. */
object MemberFinderRules {
    const val MAX_OPEN_OUTGOING: Int = 5
    const val MAX_OPEN_INCOMING: Int = 3
    const val NOTE_MAX_LENGTH: Int = 200
    const val OPEN_DAYS: Int = 30
    const val CLOSED_RETENTION_DAYS: Int = 7
    const val ACCEPTED_RETENTION_DAYS: Int = 90
    const val REGRANT_COOLDOWN_HOURS: Int = 24

    /** `null` below [MEMBER_FINDER_MIN_REGION_SIZE]; otherwise the coarse band of [count]. */
    fun bandOf(count: Int): MemberFinderBand? =
        when {
            count < MEMBER_FINDER_MIN_REGION_SIZE -> null
            count < 10 -> MemberFinderBand.B5_9
            count < 20 -> MemberFinderBand.B10_19
            else -> MemberFinderBand.B20_PLUS
        }

    /**
     * Trims, removes control characters (C0, DEL, C1) and invisible/bidirectional controls, turns line breaks into spaces, collapses runs of
     * spaces; a blank result is `null`. More than [NOTE_MAX_LENGTH] UTF-16 code units throws [network.lapis.cloud.shared.rpc.MemberFinderNoteInvalidException].
     */
    fun normalizeNote(raw: String?): String? {
        if (raw == null) return null
        val cleaned =
            buildString {
                for (c in raw) {
                    val code = c.code
                    when {
                        c == '\n' || c == '\r' || c == '\t' -> append(' ')
                        code < 0x20 || code in 0x7F..0x9F -> Unit
                        code in 0x202A..0x202E || code in 0x2066..0x2069 || code in 0x200B..0x200F || code == 0xFEFF -> Unit
                        else -> append(c)
                    }
                }
            }.replace(Regex(" {2,}"), " ").trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.length > NOTE_MAX_LENGTH) {
            throw network.lapis.cloud.shared.rpc
                .MemberFinderNoteInvalidException()
        }
        return cleaned
    }
}

/**
 * The caller's own finder state. [regionLabel] is the two digits of the caller's OWN region and only present while the caller consents and is
 * eligible; [band] is `null` while the own region is not visible yet. [bandComputedOn] is the organization date of the daily evaluation.
 */
@Serializable
data class MemberFinderStateDto(
    val offered: Boolean,
    val eligible: Boolean,
    val optedIn: Boolean,
    val consentOutdated: Boolean,
    val interest: MemberFinderInterest?,
    val regionLabel: String?,
    val band: MemberFinderBand?,
    val bandComputedOn: LocalDate?,
    val nextOptInAllowedAt: LocalDateTime?,
    val pendingOutgoing: Int,
)

/** The consent text shown to the member; [versionLabel] and [sha256] are sent back with the opt-in. */
@Serializable
data class MemberFinderConsentTextDto(
    val versionLabel: String,
    val body: String,
    val sha256: String,
)

/** An own request as the SENDER sees it: no note, no counterpart, no timestamp finer than a date. */
@Serializable
data class MemberFinderOutgoingRequestDto(
    val requestId: String,
    val purpose: MemberFinderPurpose,
    val sentOn: LocalDate,
    val expiresOn: LocalDate,
    val state: MemberFinderOutgoingState,
)

/** A request addressed to the caller. The sender is NOT shown. */
@Serializable
data class MemberFinderIncomingRequestDto(
    val requestId: String,
    val purpose: MemberFinderPurpose,
    val receivedOn: LocalDate,
    val expiresOn: LocalDate,
    val note: String?,
)

/** A connection after both sides agreed. The only place a name appears. */
@Serializable
data class MemberFinderConnectionDto(
    val connectionId: String,
    val otherDisplayName: String,
    val purpose: MemberFinderPurpose,
    val connectedOn: LocalDate,
    val endsOn: LocalDate,
)

/** The answer to a response: the new [connection] when the request was accepted, `null` when it was declined (Kilua RPC cannot return a nullable type directly). */
@Serializable
data class MemberFinderResponseDto(
    val connection: MemberFinderConnectionDto?,
)

/** One block the caller set; the blocked person is not named. */
@Serializable
data class MemberFinderBlockDto(
    val blockId: String,
    val createdOn: LocalDate,
)

/** One region with a visible band; only regions with at least [MEMBER_FINDER_MIN_REGION_SIZE] consenting members are listed. */
@Serializable
data class MemberFinderRegionBandDto(
    val regionLabel: String,
    val band: MemberFinderBand,
)

/** What BOARD/ADMIN see: bands per region, never a count and never a person. */
@Serializable
data class MemberFinderBoardOverviewDto(
    val computedOn: LocalDate?,
    val regions: List<MemberFinderRegionBandDto>,
)
