package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import network.lapis.cloud.shared.domain.ConferenceRecordingDto
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceStreamDto
import network.lapis.cloud.shared.domain.ConferenceStreamPauseFeedState
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.RoomPauseScope
import network.lapis.cloud.shared.domain.roomPauseScopeOf

/*
 * V1.9.254 -- pause mode of the video conference: the pure client side (texts, scope, button state). Nothing here touches the DOM or an RPC.
 *
 * The state is ALWAYS read from the recording and stream DTOs the client already fetches (`ConferenceRecordingDto.paused`,
 * `ConferenceStreamDto.pauseReason == ROOM_PAUSE`); the LiveKit packet `capture_pause` / `capture_resume` only triggers a re-read. A late
 * joiner or a reload therefore sees the same banner as everybody else.
 *
 * Every sentence is its own msgid; two sentences are joined with a space, a sentence is never built from fragments.
 */

/** The stream is stopped by the room pause (the pause reason says so, not merely "paused"). */
internal fun ConferenceStreamDto?.pausedByRoom(): Boolean =
    this != null &&
        pauseReason == ConferenceStreamPauseReason.ROOM_PAUSE &&
        (status == ConferenceStreamStatus.PAUSING || status == ConferenceStreamStatus.PAUSED)

/** What the room pause currently stops, `null` when the room is not paused. */
internal fun clientRoomPauseScope(
    recording: ConferenceRecordingDto?,
    stream: ConferenceStreamDto?,
): RoomPauseScope? = roomPauseScopeOf(recordingPaused = recording?.paused == true, streamPaused = stream.pausedByRoom())

/** The first sentence of the banner: what is stopped. */
internal fun roomPauseHeadline(scope: RoomPauseScope): String =
    when (scope) {
        RoomPauseScope.BOTH -> gettext("⏸ Pause: Aufzeichnung und Live-Stream sind angehalten.")
        RoomPauseScope.RECORDING -> gettext("⏸ Pause: Die Aufzeichnung ist angehalten.")
        RoomPauseScope.STREAM -> gettext("⏸ Pause: Der Live-Stream ist angehalten.")
    }

/** The banner text of a paused room: what is stopped, and that people may keep talking. */
internal fun roomPauseSentence(scope: RoomPauseScope): String =
    roomPauseHeadline(scope) + " " + gettext("Sie können weiter miteinander sprechen.")

/**
 * V1.9.255 -- the second line of the room banner while the room pause holds the live stream: whether the server sends the pause picture to the
 * targets. `null` (no line) for every other state, so the banner never says more than is true.
 */
internal fun roomPauseFeedLine(stream: ConferenceStreamDto?): String? {
    if (!stream.pausedByRoom()) return null
    return when (stream?.pauseFeed) {
        ConferenceStreamPauseFeedState.ACTIVE -> gettext("Im Stream läuft das Pausenbild.")
        ConferenceStreamPauseFeedState.FAILED ->
            gettext("Das Pausenbild konnte nicht gesendet werden. Der Stream ist bis zum Fortsetzen unterbrochen.")
        else -> null
    }
}

/** The one badge of the mini bar / floating window while the room is paused. */
internal fun roomPauseBadge(): String = gettext("⏸ Pause")

/** What a screen reader hears when the pause starts (the badge itself is only "⏸ Pause"). */
internal fun roomPauseAnnouncement(scope: RoomPauseScope): String =
    when (scope) {
        RoomPauseScope.BOTH -> gettext("Pause: Aufzeichnung und Live-Stream angehalten")
        RoomPauseScope.RECORDING -> gettext("Pause: Aufzeichnung angehalten")
        RoomPauseScope.STREAM -> gettext("Pause: Live-Stream angehalten")
    }

/** The reason an option cannot be started while the room is paused. */
internal fun roomPausedStartReason(): String = gettext("Die Sitzung ist pausiert. Setzen Sie zuerst fort.")

/** A request of the pause button that is in flight. */
internal enum class PauseBusy { PAUSING, RESUMING }

/**
 * The pause toggle: "⏸ Pause" while the room runs, "▶ Fortsetzen" while it is paused. [available] `false` means `aria-disabled` with
 * [reason] as the description (never the `disabled` attribute -- nobody would hear why). [busy] is a request in flight.
 */
internal data class PauseButtonState(
    val label: String,
    val pressed: Boolean,
    val available: Boolean,
    val busy: Boolean,
    val reason: String?,
)

/** Whether something runs that a pause could stop: a recording that is recording, a stream that is starting or live. */
internal fun somethingToPause(
    recording: ConferenceRecordingDto?,
    stream: ConferenceStreamDto?,
): Boolean =
    (recording != null && recording.status == ConferenceRecordingStatus.RECORDING && !recording.paused) ||
        stream?.status == ConferenceStreamStatus.LIVE ||
        stream?.status == ConferenceStreamStatus.STARTING

internal fun pauseButtonStateOf(
    scope: RoomPauseScope?,
    recording: ConferenceRecordingDto?,
    stream: ConferenceStreamDto?,
    busy: PauseBusy?,
): PauseButtonState {
    if (busy != null) {
        val label = if (busy == PauseBusy.PAUSING) gettext("Wird pausiert …") else gettext("Wird fortgesetzt …")
        return PauseButtonState(label = label, pressed = busy == PauseBusy.RESUMING, available = false, busy = true, reason = null)
    }
    if (scope != null) {
        return PauseButtonState(label = gettext("▶ Fortsetzen"), pressed = true, available = true, busy = false, reason = null)
    }
    val canPause = somethingToPause(recording, stream)
    return PauseButtonState(
        label = gettext("⏸ Pause"),
        pressed = false,
        available = canPause,
        busy = false,
        reason = if (canPause) null else gettext("Weder Aufzeichnung noch Live-Stream laufen."),
    )
}

/**
 * The consent badges of the mini bar and the floating window. While the room is paused ONE "⏸ Pause" badge stands for what is stopped; a
 * running part the pause does not touch keeps its own badge, so the bar never claims more (or less) than is true.
 */
internal fun captureBadges(snapshot: DockSnapshot?): List<String> {
    if (snapshot == null) return emptyList()
    val pause = snapshot.roomPause
    return buildList {
        if (pause != null) add(roomPauseBadge())
        if (snapshot.recording && pause != RoomPauseScope.RECORDING && pause != RoomPauseScope.BOTH) add(tr("● Aufzeichnung"))
        if (snapshot.streaming && pause != RoomPauseScope.STREAM && pause != RoomPauseScope.BOTH) {
            add(if (snapshot.streamPaused) tr("◆ Live-Stream pausiert") else tr("◆ Live-Stream"))
        }
    }
}
