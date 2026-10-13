package network.lapis.cloud.client.livekit

import io.kvision.i18n.gettext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import network.lapis.cloud.shared.domain.CONFERENCE_HANDS_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.CONFERENCE_HAND_QUEUE_MAX
import network.lapis.cloud.shared.domain.ConferenceHandQueueDto

private val HAND_EPOCH = Regex("^[0-9a-f]{16}$")
private const val HAND_UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

private val HAND_IDENTITY = Regex("^(?:$HAND_UUID_PATTERN|evg-(?:$HAND_UUID_PATTERN|[0-9a-f]{32}))\$")

/**
 * V1.9.127 -- the ONE place a data packet of the hands topic becomes a [ConferenceHandQueueDto], strict by design:
 *
 *  - **never from a participant**: participants hold `can_publish_data`, so anything on this topic that carries a sending participant is
 *    a forgery ("somebody is first in line") and is dropped. Server packets (`SendData`) carry no sender;
 *  - 1..[CONFERENCE_HANDS_MAX_PAYLOAD_BYTES] bytes;
 *  - exactly `{"v":1,"e":"<16 hex>","seq":N,"ids":[...]}`: `v` is 1, `seq` a non-negative integer, at most [CONFERENCE_HAND_QUEUE_MAX] ids,
 *    each a member UUID, `evg-<uuid>` or a ticket guest's `evg-<32 hex>` (lower case), no duplicates, no extra key. Anything else -- or invalid JSON -- yields `null`.
 *    Never throws.
 */
internal fun conferenceHandsPacket(
    fromParticipant: Boolean,
    payload: ByteArray,
): ConferenceHandQueueDto? {
    if (fromParticipant) return null
    if (payload.isEmpty() || payload.size > CONFERENCE_HANDS_MAX_PAYLOAD_BYTES) return null
    val obj = runCatching { Json.parseToJsonElement(payload.decodeToString()) }.getOrNull() as? JsonObject ?: return null
    if (obj.keys != setOf("v", "e", "seq", "ids")) return null
    val version = (obj["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    if (version != 1) return null
    val epoch = (obj["e"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
    if (!HAND_EPOCH.matches(epoch)) return null
    val seq = (obj["seq"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
    if (seq < 0) return null
    val array = obj["ids"] as? JsonArray ?: return null
    if (array.size > CONFERENCE_HAND_QUEUE_MAX) return null
    val ids =
        array.map { element ->
            val primitive = element as? JsonPrimitive ?: return null
            if (!primitive.isString) return null
            val id = primitive.content
            if (!HAND_IDENTITY.matches(id)) return null
            id
        }
    if (ids.toSet().size != ids.size) return null
    return ConferenceHandQueueDto(epoch = epoch, seq = seq, identities = ids)
}

/** What the own client does after a snapshot arrived while it believes its hand is up ([ConferenceHandQueueView.reconcileSelf]). */
internal enum class SelfHandAction { RAISE_AGAIN, MARK_LOWERED_BY_OTHERS, NONE }

/**
 * The queue as this client knows it. [accept] drops everything that is not newer: another epoch (the server restarted) always wins, within
 * an epoch only a larger `seq` does -- so an out-of-order or duplicated packet can never bring an old order back.
 */
internal class ConferenceHandQueueView(
    private val self: String,
) {
    var epoch: String? = null
        private set
    private var lastSeq: Long = -1

    var order: List<String> = emptyList()
        private set

    /** `true` if [snapshot] was newer and is now the state. */
    fun accept(snapshot: ConferenceHandQueueDto): Boolean {
        if (epoch != snapshot.epoch) {
            epoch = snapshot.epoch
        } else if (snapshot.seq <= lastSeq) {
            return false
        }
        lastSeq = snapshot.seq
        order = snapshot.identities
        return true
    }

    /** The own place, 1-based, or `null` if the own hand is not in the queue. */
    fun positionOfSelf(): Int? = order.indexOf(self).takeIf { it >= 0 }?.plus(1)

    fun contains(identity: String): Boolean = identity in order

    /**
     * The own hand is believed up ([believedRaised], raised in [raisedInEpoch]) but is not in the queue: in the SAME epoch that means a
     * moderator lowered it (the client accepts it and does NOT raise again -- raising again would overrule the moderation); in another
     * epoch the server restarted and the list is empty, so the hand is raised again (at the end; the old order is gone).
     */
    fun reconcileSelf(
        believedRaised: Boolean,
        raisedInEpoch: String?,
    ): SelfHandAction =
        when {
            !believedRaised || positionOfSelf() != null -> SelfHandAction.NONE
            raisedInEpoch != null && raisedInEpoch == epoch -> SelfHandAction.MARK_LOWERED_BY_OTHERS
            else -> SelfHandAction.RAISE_AGAIN
        }
}

/**
 * Throttle of the moderators' announcements ("Hand gehoben: Anna"): a new hand is announced at once if the last announcement is at least
 * [gapMs] ago; hands that arrive earlier are collected and [flush] announces them together once the gap has passed ("3 neue Wortmeldungen").
 * The clock is a parameter. Only names the caller already has (the roster); nothing is stored beyond the pending names.
 */
internal class HandAnnouncementThrottle(
    private val now: () -> Double,
    private val gapMs: Double = 10_000.0,
) {
    private var lastAt = Double.NEGATIVE_INFINITY
    private val pendingNames = mutableListOf<String>()

    /** How many hands wait for the next [flush]. */
    val pending: Int get() = pendingNames.size

    /** The text to announce NOW for [names], or `null` if it has to wait (it is then collected for [flush]). */
    fun onRaised(names: List<String>): String? {
        if (names.isEmpty()) return null
        pendingNames += names
        return flush()
    }

    /** The text for the collected hands if the gap has passed, else `null`. */
    fun flush(): String? {
        if (pendingNames.isEmpty()) return null
        val t = now()
        if (t - lastAt < gapMs) return null
        lastAt = t
        val text =
            if (pendingNames.size == 1) {
                gettext("Hand gehoben: %1", pendingNames.single())
            } else {
                gettext("%1 neue Wortmeldungen", pendingNames.size)
            }
        pendingNames.clear()
        return text
    }

    /** Milliseconds until a collected announcement may go out (for the caller's timer); `null` if nothing waits. */
    fun delayUntilFlushMs(): Double? = if (pendingNames.isEmpty()) null else (gapMs - (now() - lastAt)).coerceAtLeast(0.0)

    fun clear() {
        pendingNames.clear()
    }
}
