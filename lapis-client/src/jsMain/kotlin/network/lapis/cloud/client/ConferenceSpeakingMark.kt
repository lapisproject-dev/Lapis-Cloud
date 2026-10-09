package network.lapis.cloud.client

/*
 * V1.9.85 -- the active-speaker mark of the video conference (client only, volatile: no server, no database, no log, no storage).
 *
 * LiveKit's `ActiveSpeakersChanged` is relayed unchanged by `LiveKitRoomSession` and fires only when the SET of speakers changes. A
 * person who talks for 20 seconds produces ONE report. A mark that lasted "1.5 s after the last push" would therefore go out in the
 * middle of a sentence. The rule is a set rule instead: marked is whoever is in the current set, or left it less than
 * [CONFERENCE_SPEAKING_MARK_WINDOW_MS] ago. [SpeakingMarkState.touch] (called by a 250 ms beat while anybody is marked) keeps the
 * members of the current set fresh.
 *
 * This file knows no DOM and is independent of the D3 `lastSpokeAtMs` map of `enterCall` (it neither reads nor writes it): the mark
 * can never reorder the grid or change the choice of the floating window.
 */

/** How long a mark outlives the last report that contained the identity. Deliberately its own value, not [FLOAT_SPEAKING_WINDOW_MS]. */
internal const val CONFERENCE_SPEAKING_MARK_WINDOW_MS = 1500L

/** Beat of the mark; runs only while somebody is marked. */
internal const val CONFERENCE_SPEAKING_MARK_TICK_MS = 250L

/** Pure: a timestamp of 0 means "never"; a clock that went backwards (`nowMs < lastSpokeAtMs`) and a window of 0 or less are never "speaking". */
internal fun isSpeakingNow(
    lastSpokeAtMs: Long,
    nowMs: Long,
    windowMs: Long,
): Boolean = lastSpokeAtMs > 0L && nowMs >= lastSpokeAtMs && nowMs - lastSpokeAtMs < windowMs

/** The set-plus-trailing-window logic, without DOM. Identities are only map keys, never written into markup. */
internal class SpeakingMarkState(
    private val windowMs: Long = CONFERENCE_SPEAKING_MARK_WINDOW_MS,
) {
    private var current: Set<String> = emptySet()
    private val lastSeen = mutableMapOf<String, Long>()

    /** A report of LiveKit: [identities] is the complete current set (it replaces the previous one). */
    fun onReport(
        identities: Collection<String>,
        nowMs: Long,
    ) {
        current = identities.toSet()
        for (identity in current) lastSeen[identity] = nowMs
    }

    /** The beat: everybody still in the current set stays fresh although LiveKit reports nothing new. Also drops stale leftovers. */
    fun touch(nowMs: Long) {
        for (identity in current) lastSeen[identity] = nowMs
        lastSeen.keys.retainAll { it in current || isSpeakingNow(lastSeen.getValue(it), nowMs, windowMs) }
    }

    fun isMarked(
        identity: String,
        nowMs: Long,
    ): Boolean = isSpeakingNow(lastSeen[identity] ?: 0L, nowMs, windowMs)

    /** Whether the beat has to keep running. */
    fun hasActive(nowMs: Long): Boolean = current.isNotEmpty() || lastSeen.values.any { isSpeakingNow(it, nowMs, windowMs) }

    fun forget(identity: String) {
        current = current - identity
        lastSeen.remove(identity)
    }

    fun clear() {
        current = emptySet()
        lastSeen.clear()
    }
}
