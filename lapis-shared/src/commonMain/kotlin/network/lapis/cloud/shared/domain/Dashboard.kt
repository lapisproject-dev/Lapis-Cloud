package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/**
 * V1.9.224 -- one thing on the dashboard that concerns the caller NOW (a missing vote, an outbid auction, an overdue contribution).
 * Never carries a title, a name or an amount.
 *
 * - [count]: affected objects, `1..`[DASHBOARD_COUNT_CAP].
 * - [params]: only digits (`^\d{1,6}$`) or ISO-8601 dates; validated on the server and again on the client.
 * - [objectId]: a UUID, only when [count] is 1 and only when the object belongs to (or is visible to) the caller. The client derives the
 *   route from a fixed table (module -> list route, detail route); the server never sends a navigation target.
 * - [priority]: see [DashboardPriority].
 * - [deadline]: ISO-8601 local date-time in the ORGANIZATION wall-clock (class B representation, seconds precision) of the earliest
 *   deadline, or `null`. A String on purpose: both sides only sort and cut the date, nobody converts it.
 */
@Serializable
data class DashboardSignalDto(
    val moduleKey: String,
    val reasonKey: String,
    val count: Int,
    val params: Map<String, String> = emptyMap(),
    val objectId: String? = null,
    val priority: Int,
    val deadline: String? = null,
)

/**
 * V1.9.224 -- the live counters of the two sidebar entries "Videokonferenz" and "Begegnungsräume": "active" (somebody is in the room /
 * a session is running) out of "total" (rooms that are open right now, not rooms that ever existed). From the database only, up to 60 s
 * old.
 */
@Serializable
data class NavCountsDto(
    val conferenceActive: Int,
    val conferenceTotal: Int,
    val encounterActive: Int,
    val encounterTotal: Int,
)

/** Priorities of a [DashboardSignalDto]; higher comes first. */
object DashboardPriority {
    const val URGENT = 300
    const val ACTION = 200
    const val INFO = 100
}

/** Largest [DashboardSignalDto.count] (anything above is shown as this value). */
const val DASHBOARD_COUNT_CAP = 99

/** The module a signal belongs to; the client maps it to a list route (and, for some, a detail route) -- never the server. */
object DashboardModuleKeys {
    const val AUCTION = "auction"
    const val TENDER = "tender"
    const val ELECTION = "election"
    const val CONSENSUS = "consensus"
    const val SURVEY = "survey"
    const val MOTION = "motion"
    const val MEETING = "meeting"
    const val CROWDFUNDING = "crowdfunding"
    const val EVENTS = "events"
    const val CONTRIBUTIONS = "contributions"
    const val TRAVEL = "travel"
    const val AI = "ai"
    const val APPLICATIONS = "applications"
    const val TREASURY = "treasury"

    val ALL: List<String> =
        listOf(
            AUCTION,
            TENDER,
            ELECTION,
            CONSENSUS,
            SURVEY,
            MOTION,
            MEETING,
            CROWDFUNDING,
            EVENTS,
            CONTRIBUTIONS,
            TRAVEL,
            AI,
            APPLICATIONS,
            TREASURY,
        )
}

/** The reason of a signal; the client has a text (singular + plural) for each. Server and client share these constants. */
object DashboardReasonKeys {
    const val AUCTION_OUTBID = "auction.outbid"
    const val AUCTION_ENDING_SOON = "auction.endingSoon"
    const val AUCTION_OWN_HAS_BIDS = "auction.ownHasBids"
    const val AUCTION_WON = "auction.won"
    const val AUCTION_SOLD = "auction.sold"
    const val TENDER_DEADLINE_SOON = "tender.deadlineSoon"
    const val TENDER_OWN_BID_DECIDED = "tender.ownBidDecided"
    const val ELECTION_NOT_VOTED = "election.notVoted"
    const val CONSENSUS_NOT_VOTED = "consensus.notVoted"
    const val SURVEY_NOT_ANSWERED = "survey.notAnswered"
    const val MOTION_VOTE_OPEN = "motion.voteOpen"
    const val MEETING_UPCOMING = "meeting.upcoming"
    const val CROWDFUNDING_OWN_DECIDED = "crowdfunding.ownDecided"
    const val EVENTS_NEXT_REGISTERED = "events.nextRegistered"
    const val CONTRIBUTIONS_OVERDUE = "contributions.overdue"
    const val CONTRIBUTIONS_OPEN = "contributions.open"
    const val TRAVEL_STATUS_CHANGED = "travel.statusChanged"
    const val AI_DRAFTS_WAITING = "ai.draftsWaiting"
    const val APPLICATIONS_OPEN = "applications.open"
    const val TREASURY_OVERDUE_NOT_IN_DUNNING = "treasury.overdueNotInDunning"

    val ALL: List<String> =
        listOf(
            AUCTION_OUTBID,
            AUCTION_ENDING_SOON,
            AUCTION_OWN_HAS_BIDS,
            AUCTION_WON,
            AUCTION_SOLD,
            TENDER_DEADLINE_SOON,
            TENDER_OWN_BID_DECIDED,
            ELECTION_NOT_VOTED,
            CONSENSUS_NOT_VOTED,
            SURVEY_NOT_ANSWERED,
            MOTION_VOTE_OPEN,
            MEETING_UPCOMING,
            CROWDFUNDING_OWN_DECIDED,
            EVENTS_NEXT_REGISTERED,
            CONTRIBUTIONS_OVERDUE,
            CONTRIBUTIONS_OPEN,
            TRAVEL_STATUS_CHANGED,
            AI_DRAFTS_WAITING,
            APPLICATIONS_OPEN,
            TREASURY_OVERDUE_NOT_IN_DUNNING,
        )
}
