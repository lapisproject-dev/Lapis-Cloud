package network.lapis.cloud.client

import kotlinx.browser.sessionStorage
import kotlin.js.Date

/*
 * Room polls, wave B -- "log in, then come back to the viewer page". There is deliberately NO generic return target: `safeReturnTo` only accepts
 * the OAuth authorize path and stays untouched (it is security-critical). This is a second, much narrower mechanism: the page remembers only the
 * ROOM ID (a strictly validated lower-case UUID) with a time stamp in `sessionStorage`; after a login (and after the SSO / federation round trip
 * that lands on the start page) the target is REBUILT from the validated id. The stored text itself is never used as a route. Every storage access
 * is wrapped: a private window or blocked storage simply means "no return".
 */

internal const val ROOM_POLL_RETURN_KEY = "lapis.roomPollReturn"
internal const val ROOM_POLL_RETURN_TTL_MS = 30 * 60 * 1000.0
private const val SEPARATOR = "|"

/** Remembers [roomId] (only if it is a valid room id) for the next login. */
internal fun rememberRoomPollReturn(
    roomId: String?,
    nowMs: Double = Date.now(),
) {
    if (!isRoomPollRoomId(roomId)) return
    try {
        sessionStorage.setItem(ROOM_POLL_RETURN_KEY, "$roomId$SEPARATOR${nowMs.toLong()}")
    } catch (ignored: Throwable) {
        // blocked storage: the login simply does not return to the page
    }
}

/** Reads and removes the remembered room id; `null` when there is none, it is older than 30 minutes or it is not a valid id. */
internal fun consumeRoomPollReturn(nowMs: Double = Date.now()): String? {
    val stored =
        try {
            sessionStorage.getItem(ROOM_POLL_RETURN_KEY)
        } catch (e: Throwable) {
            null
        } ?: return null
    try {
        sessionStorage.removeItem(ROOM_POLL_RETURN_KEY)
    } catch (ignored: Throwable) {
        // nothing more to do
    }
    val parts = stored.split(SEPARATOR)
    if (parts.size != 2) return null
    val savedAt = parts[1].toLongOrNull() ?: return null
    val age = nowMs - savedAt
    if (age < 0 || age > ROOM_POLL_RETURN_TTL_MS) return null
    return parts[0].takeIf { isRoomPollRoomId(it) }
}

/** The route of the viewer page, rebuilt from a validated id (never from stored text). */
internal fun roomPollViewerRoute(roomId: String): String = "/room-poll/$roomId"

/** After a login or on the start page: the viewer page if a return is remembered, else `false` (the caller goes to its default). */
internal fun continueToRoomPollViewer(): Boolean {
    val id = consumeRoomPollReturn() ?: return false
    navigateTo(roomPollViewerRoute(id))
    return true
}
