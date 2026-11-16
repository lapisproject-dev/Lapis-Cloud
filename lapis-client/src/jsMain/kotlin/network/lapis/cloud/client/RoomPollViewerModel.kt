package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.RoomPollRules
import network.lapis.cloud.shared.domain.RoomPollStateDto
import network.lapis.cloud.shared.domain.RoomPollStatus

/*
 * Room polls, wave B -- the DOM-free part of the viewer page (`#/room-poll/<roomId>`, the page a stream viewer opens from a link or QR code):
 * which phase the page shows, how long it waits before the next call, what a screen reader hears. Pure functions, pinned by `jsTest` without a
 * browser; the DOM lives in RoomPollViewerScreen.kt, the timing discipline in RoomPollViewerController.kt.
 */

/** What the viewer page shows. [LOGIN] and [UNAVAILABLE] are decided by the screen (no session / a refusal), the others by the state. */
internal enum class RoomPollViewerPhase { LOADING, NO_POLL, OPEN, ANSWERED, CLOSED, UNAVAILABLE, LOGIN }

/** The phase of a state the server returned; `null` = nothing received yet. */
internal fun roomPollViewerPhase(state: RoomPollStateDto?): RoomPollViewerPhase {
    val poll = state?.poll
    return when {
        state == null -> RoomPollViewerPhase.LOADING
        poll == null -> RoomPollViewerPhase.NO_POLL
        poll.status == RoomPollStatus.OPEN -> if (state.voted) RoomPollViewerPhase.ANSWERED else RoomPollViewerPhase.OPEN
        else -> RoomPollViewerPhase.CLOSED
    }
}

/**
 * The wait before the next version call: [RoomPollRules.VIEWER_VERSION_POLL_MS] plus a random 0 until [RoomPollRules.VIEWER_VERSION_JITTER_MS]
 * while the tab is visible (so at most 12 calls a minute), 30 s in a hidden tab, the backoff table after failures, one 60 s pause after a read
 * the server answered with a conflict (the rate limit looks like any other conflict on the wire).
 */
internal fun roomPollViewerNextDelayMs(
    hidden: Boolean,
    failures: Int,
    ratePaused: Boolean,
    random: () -> Double,
): Int {
    val base =
        if (hidden) {
            RoomPollRules.VIEWER_HIDDEN_POLL_MS
        } else {
            RoomPollRules.VIEWER_VERSION_POLL_MS +
                (random() * RoomPollRules.VIEWER_VERSION_JITTER_MS).toInt().coerceIn(0, RoomPollRules.VIEWER_VERSION_JITTER_MS - 1)
        }
    return when {
        ratePaused -> maxOf(RoomPollRules.VIEWER_RATE_PAUSE_MS, base)
        failures > 0 -> maxOf(RoomPollRules.VIEWER_BACKOFF_MS[(failures - 1).coerceAtMost(RoomPollRules.VIEWER_BACKOFF_MS.lastIndex)], base)
        else -> base
    }
}

/** A change the person did not cause is announced; the first load and an unchanged phase never are. `null` = nothing to say. */
internal fun roomPollViewerAnnouncement(
    prev: RoomPollViewerPhase?,
    next: RoomPollViewerPhase,
    firstLoad: Boolean,
): String? =
    when {
        firstLoad || prev == null || prev == next -> null
        next == RoomPollViewerPhase.OPEN -> gettext("Eine Umfrage hat begonnen.")
        next == RoomPollViewerPhase.CLOSED && (prev == RoomPollViewerPhase.OPEN || prev == RoomPollViewerPhase.ANSWERED) ->
            gettext("Die Umfrage ist beendet. Das Ergebnis wird angezeigt.")
        else -> null
    }

private val ROOM_POLL_ROOM_ID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

/** The strict form of a room id this page accepts (lower-case UUID); everything else shows the page as unavailable without any call. */
internal fun isRoomPollRoomId(raw: String?): Boolean = raw != null && ROOM_POLL_ROOM_ID_PATTERN.matches(raw)
