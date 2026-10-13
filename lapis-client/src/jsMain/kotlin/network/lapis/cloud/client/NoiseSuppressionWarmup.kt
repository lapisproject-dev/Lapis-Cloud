package network.lapis.cloud.client

import network.lapis.cloud.client.livekit.browserNoiseAudioContext
import kotlin.js.Promise

/**
 * V1.9.124 -- holds what must exist BEFORE a conference session does: the one shared remembered choice, the instance default the server
 * announced (availability answer / join token) and an audio context that was started inside the person's join click.
 *
 * Why: a browser starts an audio context only inside a gesture, and the filter chain of a session does not exist yet when the person clicks
 * "join" (there is no session, no microphone track). Chromium counts the join click as a "sticky activation", Safari and Firefox do not --
 * there the context created here, inside the click, is the one the session takes over later ([take]). Where it still does not run, the
 * "next gesture" hook of [ConferenceNoiseSuppressionController] remains the second line (nothing here replaces it).
 *
 * Rules: [unlockFromGesture] is synchronous, never throws, and creates nothing when the filter would not be on anyway (the person chose
 * off, or never chose and the default is off). At most ONE context exists; [take] hands it over exactly once, so no two sessions close
 * the same one. The pulpit of the encounter room does not use any of this.
 */
internal object NoiseSuppressionWarmup {
    /** The one remembered-choice reader the conference shares with this holder (so a blocked `localStorage` stays consistent in memory). */
    val preference: NoiseSuppressionPreference = LocalStorageNoiseSuppressionPreference()

    /** The instance default the server announced last; `null` = not announced yet (then only the remembered choice counts). */
    var knownDefault: Boolean? = null
        private set

    /** Test seam: builds the context (`null` = this browser has none). */
    internal var contextFactory: () -> dynamic = ::browserNoiseAudioContext

    /** Test seam: the browser capability probe. */
    internal var supportProbe: () -> NoiseSuppressionUnavailable? = ::noiseSuppressionSupportInThisBrowser

    private var warm: dynamic = null

    fun remember(default: Boolean) {
        knownDefault = default
    }

    /** Called synchronously inside a click. [effectiveOn] = whether the filter would be wanted now (the remembered choice wins over the default). */
    fun unlockFromGesture(effectiveOn: Boolean = runCatching { preference.read() }.getOrNull() ?: knownDefault ?: false) {
        if (!effectiveOn) return
        runCatching {
            if (supportProbe() != null) return
            var ctx: dynamic = warm
            if (ctx == null || ctx == undefined || ctx.state == "closed") {
                ctx = contextFactory()
                warm = if (ctx == undefined) null else ctx
            }
            if (ctx != null && ctx != undefined) {
                val resumed: dynamic = ctx.resume()
                if (resumed != null && resumed != undefined) {
                    runCatching { resumed.unsafeCast<Promise<dynamic>>().catch { null } }
                }
            }
        }
    }

    /** Hands the warm context over (once) and empties the holder; `null` when there is none or it was closed meanwhile. */
    fun take(): dynamic {
        val ctx = warm
        warm = null
        if (ctx == null || ctx == undefined) return null
        return if (ctx.state == "closed") null else ctx
    }
}
