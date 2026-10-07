package network.lapis.cloud.client

import network.lapis.cloud.client.livekit.DisconnectCause

/**
 * V1.9.69 -- bounds the AUTOMATIC re-join after a disconnect (a second sign-in with the same account used to make two
 * devices evict each other endlessly). The clock is injected; no real time in the logic.
 *
 * A timestamp counts for exactly [windowMs]: at `now - t >= windowMs` it no longer counts (so a re-join exactly one
 * window after the first is allowed again).
 */
internal class AutoRejoinGuard(
    private val maxAttempts: Int = 3,
    private val windowMs: Double = 60_000.0,
    private val now: () -> Double,
) {
    private val stamps = ArrayDeque<Double>()

    /** `true` = one automatic re-join may run now (and is recorded); `false` = stop. */
    fun tryConsume(): Boolean {
        val t = now()
        while (stamps.isNotEmpty() && t - stamps.first() >= windowMs) stamps.removeFirst()
        if (stamps.size >= maxAttempts) return false
        stamps.addLast(t)
        return true
    }

    /** A deliberate click ("Hier fortsetzen" / "Erneut beitreten") starts a fresh window. */
    fun reset() {
        stamps.clear()
    }
}

/** What [decideAfterDisconnect] tells the screen to do next. */
internal sealed class PostDisconnectAction {
    /** DUPLICATE_IDENTITY: the same account joined elsewhere. No RPC, no automatic join. */
    data object Displaced : PostDisconnectAction()

    /** The automatic re-join guard is exhausted. */
    data object LoopStopped : PostDisconnectAction()

    data object Ended : PostDisconnectAction()

    data class Breakout(
        val destination: PostDisconnectDestination.Breakout,
    ) : PostDisconnectAction()

    data class RejoinMain(
        val destination: PostDisconnectDestination.Main,
    ) : PostDisconnectAction()
}

/**
 * Pure. [destination] is `null` iff [cause] is [DisconnectCause.DuplicateIdentity] (the caller skips the RPCs).
 * [tryConsumeAutoRejoin] is invoked ONLY for a Main destination; the Breakout path is deliberately unguarded (it is
 * only triggered by a moderator's action).
 */
internal fun decideAfterDisconnect(
    cause: DisconnectCause,
    destination: PostDisconnectDestination?,
    tryConsumeAutoRejoin: () -> Boolean,
): PostDisconnectAction {
    if (cause == DisconnectCause.DuplicateIdentity) return PostDisconnectAction.Displaced
    return when (destination) {
        null, is PostDisconnectDestination.Ended -> PostDisconnectAction.Ended
        is PostDisconnectDestination.Breakout -> PostDisconnectAction.Breakout(destination)
        is PostDisconnectDestination.Main ->
            if (tryConsumeAutoRejoin()) PostDisconnectAction.RejoinMain(destination) else PostDisconnectAction.LoopStopped
    }
}
