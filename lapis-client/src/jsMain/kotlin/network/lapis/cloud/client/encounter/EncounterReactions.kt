package network.lapis.cloud.client.encounter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import network.lapis.cloud.client.livekit.TextDecoder
import network.lapis.cloud.client.livekit.TextEncoder
import network.lapis.cloud.shared.domain.ENCOUNTER_REACTION_MAX_PAYLOAD_BYTES
import network.lapis.cloud.shared.domain.EncounterReaction
import network.lapis.cloud.shared.domain.EncounterReactionOption
import network.lapis.cloud.shared.domain.option
import org.khronos.webgl.Uint8Array

/**
 * V1.9.62 Begegnungsraum (B2) -- the data-channel wire format of the congregation's reactions and the pure state around it. Everything
 * here is DOM-free and takes its clock as a parameter (`now` in milliseconds), so a test can drive minutes of behaviour without waiting.
 *
 * ## Trust boundary
 * A reaction packet is peer-to-peer and unauthenticated beyond LiveKit's own transport. The payload is `{"r":"AMEN"}` (or APPLAUSE, HEART, HAND, HAND_LOWERED) and nothing
 * else: [EncounterReactionWire.decode] reads ONLY the key `r`, ignores every other field (a forged `sender`/`name` is never looked
 * at), refuses anything longer than [ENCOUNTER_REACTION_MAX_PAYLOAD_BYTES] unread and never throws. The sender of a reaction is the
 * SDK-verified participant identity the caller passes next to the payload ([network.lapis.cloud.client.livekit.LiveKitRoomSession]).
 *
 * ## Privacy
 * Nothing here keeps a time per person beyond the seconds a flood guard needs, nothing is written to storage, nothing is counted
 * for display: an amen is a symbol at a seat, never a number.
 */
internal object EncounterReactionWire {
    fun encode(reaction: EncounterReaction): Uint8Array = TextEncoder().encode("{\"r\":\"${reaction.name}\"}")

    /** The reaction of [bytes], or `null` for an over-long, malformed or unknown payload. Never throws. */
    fun decode(bytes: Uint8Array): EncounterReaction? =
        try {
            if (bytes.length > ENCOUNTER_REACTION_MAX_PAYLOAD_BYTES) {
                null
            } else {
                decodeText(TextDecoder().decode(bytes))
            }
        } catch (e: Throwable) {
            null
        }

    /** The text half of [decode], separately testable without a `Uint8Array`. */
    internal fun decodeText(text: String): EncounterReaction? {
        val element = Json.parseToJsonElement(text) as? JsonObject ?: return null
        val primitive = element["r"] as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return EncounterReaction.entries.firstOrNull { it.name == primitive.content }
    }
}

/** The wire reaction an option is sent as (the hand is sent as [EncounterReaction.HAND]; lowering it is [EncounterReaction.HAND_LOWERED]). */
internal fun EncounterReactionOption.toWire(): EncounterReaction =
    when (this) {
        EncounterReactionOption.HAND -> EncounterReaction.HAND
        EncounterReactionOption.AMEN -> EncounterReaction.AMEN
        EncounterReactionOption.APPLAUSE -> EncounterReaction.APPLAUSE
        EncounterReactionOption.HEART -> EncounterReaction.HEART
    }

/**
 * V1.9.67: the receive filter of the configured reaction set. A reaction is admitted only when its option ([EncounterReaction.option])
 * is among the options the room allows; [EncounterReaction.HAND_LOWERED] belongs to the hand and is therefore always admitted (HAND is
 * always on). A dropped reaction leaves no trace: no log, no counter. The set is enforced on the client only (the server never reads a
 * reaction); a manipulated client can send anything, so this is a courtesy of the honest client, not a security boundary.
 */
internal fun admitReaction(
    reaction: EncounterReaction,
    allowed: Set<EncounterReactionOption>,
): Boolean = reaction.option() in allowed

/**
 * Sender-side rate limits: one EVENT reaction (amen, applause, heart share one budget) per 5 s, one hand toggle per 2 s. The hand is never
 * blocked by an event: a person who wants the floor can always ask for it. A refused call has no effect (the UI shows the button as waiting).
 */
internal class EncounterReactionSendThrottle(
    private val now: () -> Double,
) {
    private var lastEvent = Double.NEGATIVE_INFINITY
    private var lastHand = Double.NEGATIVE_INFINITY

    fun tryEvent(): Boolean = take(lastEvent, EVENT_GAP_MS) { lastEvent = it }

    fun tryHandToggle(): Boolean = take(lastHand, HAND_GAP_MS) { lastHand = it }

    private fun take(
        last: Double,
        gap: Double,
        store: (Double) -> Unit,
    ): Boolean {
        val t = now()
        if (t - last < gap) return false
        store(t)
        return true
    }

    companion object {
        const val EVENT_GAP_MS = 5_000.0
        const val HAND_GAP_MS = 2_000.0
    }
}

/**
 * Receiver-side flood guard: more than [MAX_PER_WINDOW] reactions per sender within [WINDOW_MS] are dropped (a hostile or broken
 * client cannot flood the room's screens). The table is bounded to [MAX_SENDERS] senders so a stream of fresh identities cannot grow it
 * without limit; the oldest entry makes room.
 */
internal class EncounterReactionReceiveGuard(
    private val now: () -> Double,
) {
    private val seen = LinkedHashMap<String, MutableList<Double>>()

    fun admit(sender: String): Boolean {
        val t = now()
        val stamps = seen.remove(sender) ?: mutableListOf()
        stamps.removeAll { t - it >= WINDOW_MS }
        val allowed = stamps.size < MAX_PER_WINDOW
        if (allowed) stamps += t
        // re-inserted last: the iteration order is the order of the most recent activity
        seen[sender] = stamps
        while (seen.size > MAX_SENDERS) seen.remove(seen.keys.first())
        return allowed
    }

    companion object {
        const val MAX_PER_WINDOW = 2
        const val WINDOW_MS = 1_000.0
        const val MAX_SENDERS = 64
    }
}

/**
 * The raised hands of the room. A hand is a STATE its owner renews every 30 s; one that is not renewed for [ttlMs] (90 s) lapses by
 * itself ([expire]), so a person who left without lowering it does not stay "raised" forever. [ordered] lists identities by the
 * moment they FIRST raised their hand -- a renewal does not move anyone back -- which is the order an office holder calls them in.
 */
internal class EncounterRaisedHands(
    private val now: () -> Double,
    private val ttlMs: Double = 90_000.0,
) {
    private val lastSeen = LinkedHashMap<String, Double>()

    fun raise(identity: String) {
        // putting an existing key keeps its position in a LinkedHashMap: a renewal keeps the place in the queue
        lastSeen[identity] = now()
    }

    fun lower(identity: String) {
        lastSeen.remove(identity)
    }

    /** Drops every hand that was not renewed within the ttl and returns who lapsed. */
    fun expire(): List<String> {
        val t = now()
        val lapsed = lastSeen.filterValues { t - it > ttlMs }.keys.toList()
        lapsed.forEach { lastSeen.remove(it) }
        return lapsed
    }

    val ordered: List<String> get() = lastSeen.keys.toList()

    fun contains(identity: String): Boolean = lastSeen.containsKey(identity)
}

/**
 * Bundles the polite announcement of event reactions (amen, applause, heart): at most one announcement per [minGapMs] (10 s), however
 * many arrive, and never a number -- the caller shows one fixed sentence. `true` = announce now.
 */
internal class EncounterEventAnnouncer(
    private val now: () -> Double,
    private val minGapMs: Double = 10_000.0,
) {
    private var last = Double.NEGATIVE_INFINITY

    fun onEvent(): Boolean {
        val t = now()
        if (t - last < minGapMs) return false
        last = t
        return true
    }
}
