package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The teardown hook of the video-conference screen in a REAL, mounted `Root` (the "late hooks" audit).
 *
 * `renderConferenceScreen`'s destroy hook removes the `beforeunload` listener that disconnects a running
 * LiveKit session on tab close, and resets [ConferenceCallPresence]. It must run when the screen really
 * goes away -- not once, right after the screen came up, because the hook was registered after the screen
 * root had already been rendered.
 *
 * Observable used here: [ConferenceCallPresence.live], which the hook resets. The RPC behind the screen
 * fails under Karma (no server), which is fine -- the failure path patches the screen root
 * (`statusLine.content = ...`), and that patch is exactly what used to fire the hook early.
 */
class ConferenceScreenRootLifecycleDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    @Test
    fun screenTeardown_doesNotRunWhileTheScreenIsStillShown(): Promise<Unit> =
        test {
            withMountedRoot("conference-root-lifecycle-test") { root, _ ->
                ConferenceCallPresence.set(live = true)
                try {
                    renderConferenceScreen(root)
                    delay(500) // the availability RPC has failed by now and the screen patched itself
                    assertTrue(ConferenceCallPresence.live, "the teardown hook must not have run: the screen is still mounted")
                    root.removeAll()
                    assertTrue(!ConferenceCallPresence.live, "leaving the screen resets the flag")
                } finally {
                    ConferenceCallPresence.set(live = false)
                }
            }
        }
}
