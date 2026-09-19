package network.lapis.cloud.client

/**
 * Welle V1.4.20 -- whether a video-conference call is (possibly) still alive in THIS tab. The
 * "new version available" hint must never appear over a running call (see
 * [shouldShowBanner]'s `callLive`), so `ConferenceScreen.kt` mirrors its connection state machine
 * into this flag: set in `transition(...)` (the only place `connectionState` is assigned) and reset
 * in the screen's `addAfterDestroyHook`, so navigating away mid-call can never strand it `true`.
 *
 * "Live" means Connecting / Connected / Reconnecting AND Resolving: after a transport disconnect
 * the call may still be resumed (breakout hand-off), and a "Neu laden" button must not end it
 * unannounced -- conservative on purpose.
 */
internal object ConferenceCallPresence {
    var live: Boolean = false
        private set

    fun set(live: Boolean) {
        if (this.live != live) {
            this.live = live
            ClientVersionWatcher.notifyCallLiveChanged()
        }
    }
}
