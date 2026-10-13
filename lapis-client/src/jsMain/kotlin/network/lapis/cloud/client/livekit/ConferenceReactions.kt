package network.lapis.cloud.client.livekit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import network.lapis.cloud.shared.domain.CONFERENCE_REACTION_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.ConferenceReaction
import org.khronos.webgl.Uint8Array

/**
 * V1.9.127 -- the data-channel wire format of the conference reactions (topic [network.lapis.cloud.shared.domain.CONFERENCE_REACTION_TOPIC])
 * and the flood guards around it. DOM-free (the clock is a parameter), so a test can drive minutes of behaviour without waiting.
 *
 * ## Trust boundary
 * A reaction packet is peer to peer. The payload is exactly `{"r":"APPLAUSE"}` (or HEART, AMEN, THUMBS_UP, FLOWER) and nothing else:
 * [decode] accepts ONLY an object whose one key is `r`, refuses anything longer than [CONFERENCE_REACTION_MAX_PAYLOAD_BYTES] unread,
 * knows no `HAND` (the raised hand is the server-ordered queue, never a peer packet) and never throws. The sender of a reaction is the
 * SDK-verified participant identity the caller passes next to the payload, never a payload field.
 *
 * ## Privacy
 * Nothing here is written to storage, nothing is counted for display or sent to the server: a reaction is a mood, shown for seconds.
 */
internal object ConferenceReactionWire {
    fun encode(reaction: ConferenceReaction): Uint8Array = TextEncoder().encode("{\"r\":\"${reaction.name}\"}")

    /** The reaction of [bytes], or `null` for an over-long, malformed or unknown payload. Never throws. */
    fun decode(bytes: Uint8Array): ConferenceReaction? =
        try {
            if (bytes.length > CONFERENCE_REACTION_MAX_PAYLOAD_BYTES) null else decodeText(TextDecoder().decode(bytes))
        } catch (e: Throwable) {
            null
        }

    /** The text half of [decode], separately testable without a `Uint8Array`. Never throws. */
    internal fun decodeText(text: String): ConferenceReaction? =
        try {
            if (text.encodeToByteArray().size > CONFERENCE_REACTION_MAX_PAYLOAD_BYTES) {
                null
            } else {
                val element = Json.parseToJsonElement(text) as? JsonObject
                val primitive = element?.takeIf { it.keys == setOf("r") }?.get("r") as? JsonPrimitive
                if (primitive == null || !primitive.isString) {
                    null
                } else {
                    ConferenceReaction.entries.firstOrNull { it.name == primitive.content }
                }
            }
        } catch (e: Throwable) {
            null
        }
}

/** Sender-side rate limit: one reaction per second. A refused call has no effect (the menu simply does not send). */
internal class ConferenceReactionSendThrottle(
    private val now: () -> Double,
) {
    private var last = Double.NEGATIVE_INFINITY

    fun tryEvent(): Boolean {
        val t = now()
        if (t - last < GAP_MS) return false
        last = t
        return true
    }

    companion object {
        const val GAP_MS = 1_000.0
    }
}

/**
 * Receiver-side flood guard: more than [max] reactions per sender within [windowMs] are dropped (a hostile or broken client cannot
 * flood the room's screens). The table is bounded to [maxSenders] senders so a stream of fresh identities cannot grow it without
 * limit; the oldest entry makes room.
 */
internal class ConferenceReactionReceiveGuard(
    private val now: () -> Double,
    private val max: Int = 5,
    private val windowMs: Double = 5_000.0,
    private val maxSenders: Int = 64,
) {
    private val seen = LinkedHashMap<String, MutableList<Double>>()

    fun admit(sender: String): Boolean {
        val t = now()
        val stamps = seen.remove(sender) ?: mutableListOf()
        stamps.removeAll { t - it >= windowMs }
        val allowed = stamps.size < max
        if (allowed) stamps += t
        // re-inserted last: the iteration order is the order of the most recent activity
        seen[sender] = stamps
        while (seen.size > maxSenders) seen.remove(seen.keys.first())
        return allowed
    }
}
