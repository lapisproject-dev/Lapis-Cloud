package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.RoomBallotDto
import network.lapis.cloud.shared.domain.RoomBallotKind
import network.lapis.cloud.shared.domain.RoomBallotStatus
import network.lapis.cloud.shared.domain.SystemicConsensusStatus

/*
 * V1.9.32 "Konsensieren im Konferenzraum" -- the pure rules of a consensus ballot in the room panel. No DOM, no RPC, no rating: only the
 * state the room DTO carries (kind, status, phase, own flags), so every rule is tested without a browser.
 *
 * The server maps COLLECTION and RATING both to `OPEN`; the real phase travels in `consensusPhase`. Which of the two a member can act on
 * -- and which of them locks the stream -- is decided here and nowhere else.
 */

/** Only the RATING phase can be rated; COLLECTION (options are still being collected) is `OPEN` for the room but nothing to act on. */
internal fun RoomBallotDto.isConsensusRating(): Boolean =
    kind == RoomBallotKind.CONSENSUS && consensusPhase == SystemicConsensusStatus.RATING

/**
 * A secret ballot that the SERVER locks the stream for: an election that is OPEN + secret, a consensus in RATING + anonymous. Never an
 * anonymous consensus in COLLECTION -- the server pauses the stream only when the options are frozen, so a banner or a 5-second stream
 * poll before that would announce something that is not happening.
 */
internal fun RoomBallotDto.isSecretBallotRunning(): Boolean =
    secret && status == RoomBallotStatus.OPEN && (kind != RoomBallotKind.CONSENSUS || isConsensusRating())

/** The member can act on this ballot right now (badge, auto-open): open, eligible, not yet voted, and for a consensus in RATING. */
internal fun RoomBallotDto.memberActionable(): Boolean =
    status == RoomBallotStatus.OPEN &&
        ownEligible &&
        !ownHasVoted &&
        (kind == RoomBallotKind.ELECTION || kind == RoomBallotKind.VOTE || isConsensusRating())

/** An open ballot of a kind a member can vote in. A consensus counts only while it is RATING. */
internal fun RoomBallotDto.isOpenForMembers(): Boolean =
    status == RoomBallotStatus.OPEN &&
        (kind == RoomBallotKind.ELECTION || kind == RoomBallotKind.VOTE || isConsensusRating())

/**
 * The key under which an open ballot is remembered as "seen". An election or vote is remembered by its id for good; a consensus by
 * (id, RATING) -- and the key is dropped once the consensus leaves RATING, so a re-rating after a result opens the room panel again.
 */
internal fun RoomBallotDto.openSeenKey(): String = if (kind == RoomBallotKind.CONSENSUS) "$id#rating" else id

internal fun consensusPhaseLabel(phase: SystemicConsensusStatus): String =
    when (phase) {
        SystemicConsensusStatus.COLLECTION -> gettext("Optionen werden gesammelt")
        SystemicConsensusStatus.RATING -> gettext("Bewertung läuft")
        SystemicConsensusStatus.CLOSED -> gettext("Bewertung geschlossen")
        SystemicConsensusStatus.EVALUATED -> gettext("Ausgewertet")
        // never delivered by the server; kept exhaustive so a new phase is a compile error here
        SystemicConsensusStatus.ABORTED -> gettext("Abgebrochen")
    }

/** The in-app address of a consensus; `null` unless [id] has the shape of an id this server mints. Never a foreign URL. */
internal fun conferenceConsensusDetailHref(id: String): String? = if (ROOM_ID_PATTERN.matches(id)) "#/consensus/$id" else null
