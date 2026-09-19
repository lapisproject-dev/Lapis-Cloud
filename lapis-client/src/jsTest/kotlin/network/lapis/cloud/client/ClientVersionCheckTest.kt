package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val OWN = "0123456789abcdef"
private const val OTHER = "fedcba9876543210"
private const val NOW = 1_000_000L

private fun known() = VersionCheckState(ownBuildId = OWN)

private fun outdatedState() = onFetched(state = known(), serverBuildId = OTHER, nowMs = NOW)

/**
 * V1.4.20 -- exercises the pure "new version available" state machine in `ClientVersionCheck.kt`
 * (no DOM, no clock: `nowMs`/`jitter01` are arguments).
 */
class ClientVersionCheckTest {
    @Test
    fun sameBuildId_isNotOutdated_noBanner() {
        val s = onFetched(state = known(), serverBuildId = OWN, nowMs = NOW)
        assertFalse(s.outdated)
        assertFalse(shouldShowBanner(state = s, nowMs = NOW, callLive = false))
    }

    @Test
    fun differentBuildId_isOutdated_showsBanner() {
        val s = outdatedState()
        assertTrue(s.outdated)
        assertEquals(OTHER, s.knownServerBuildId)
        assertTrue(shouldShowBanner(state = s, nowMs = NOW, callLive = false))
    }

    @Test
    fun unknownOwnBuildId_neverChecksNeverShows() {
        val dev = VersionCheckState(ownBuildId = null)
        assertFalse(shouldCheckNow(state = dev, nowMs = NOW, visible = true))
        assertFalse(shouldShowBanner(state = dev, nowMs = NOW, callLive = false))
        // Even a (bogus) fetch result must not flip a build without a known own id.
        val afterFetch = onFetched(state = dev, serverBuildId = OTHER, nowMs = NOW)
        assertFalse(afterFetch.outdated)
        assertFalse(shouldShowBanner(state = afterFetch, nowMs = NOW, callLive = false))
    }

    @Test
    fun snooze_hidesBanner_untilInclusiveEnd() {
        val s = onDismiss(state = outdatedState(), nowMs = NOW)
        val until = NOW + VERSION_CHECK_SNOOZE_MS
        assertEquals(until, s.snoozedUntilMs)
        assertFalse(shouldShowBanner(state = s, nowMs = NOW + 1, callLive = false))
        assertFalse(shouldShowBanner(state = s, nowMs = until - 1, callLive = false))
        assertTrue(shouldShowBanner(state = s, nowMs = until, callLive = false))
    }

    @Test
    fun rpcFailure_whileSnoozedAndOutdated_liftsSnooze() {
        val snoozed = onDismiss(state = outdatedState(), nowMs = NOW)
        assertFalse(shouldShowBanner(state = snoozed, nowMs = NOW + 10, callLive = false))
        val lifted = onRpcFailure(snoozed)
        assertTrue(shouldShowBanner(state = lifted, nowMs = NOW + 10, callLive = false))
    }

    @Test
    fun rpcFailure_withoutOutdated_changesNothing() {
        val s = known()
        assertEquals(s, onRpcFailure(s))
    }

    @Test
    fun liveCall_suppressesBanner_thenItReturnsWithStateIntact() {
        val s = outdatedState()
        assertFalse(shouldShowBanner(state = s, nowMs = NOW, callLive = true))
        assertTrue(shouldShowBanner(state = s, nowMs = NOW, callLive = false))
    }

    @Test
    fun jitter_boundsAndClamping() {
        val s = known()
        assertEquals(240_000L, nextDelayMs(state = s, jitter01 = 0.0))
        assertEquals(360_000L, nextDelayMs(state = s, jitter01 = 1.0))
        assertEquals(240_000L, nextDelayMs(state = s, jitter01 = -1.0))
        assertEquals(360_000L, nextDelayMs(state = s, jitter01 = 2.0))
    }

    @Test
    fun backoff_doublesPerFailure_isCapped_andResetsOnSuccess() {
        var s = known()
        val expected = listOf(480_000L, 960_000L, 1_800_000L)
        expected.forEachIndexed { i, want ->
            s = onFetchFailed(state = s, nowMs = NOW)
            assertEquals(i + 1, s.consecutiveFailures)
            assertEquals(want, nextDelayMs(state = s, jitter01 = 0.0))
        }
        // failure counter is capped at 3
        s = onFetchFailed(state = s, nowMs = NOW)
        assertEquals(3, s.consecutiveFailures)
        assertEquals(1_800_000L, nextDelayMs(state = s, jitter01 = 1.0))
        s = onFetched(state = s, serverBuildId = OWN, nowMs = NOW)
        assertEquals(0, s.consecutiveFailures)
    }

    @Test
    fun hiddenTab_neverChecks() {
        assertFalse(shouldCheckNow(state = known(), nowMs = NOW + 10 * VERSION_CHECK_MIN_GAP_MS, visible = false))
        assertTrue(shouldCheckNow(state = known(), nowMs = NOW, visible = true))
    }

    @Test
    fun minGap_isEnforced_inclusiveAtTheBoundary() {
        val s = known().copy(lastCheckAtMs = NOW)
        assertFalse(shouldCheckNow(state = s, nowMs = NOW + VERSION_CHECK_MIN_GAP_MS - 1, visible = true))
        assertTrue(shouldCheckNow(state = s, nowMs = NOW + VERSION_CHECK_MIN_GAP_MS, visible = true))
        // backwards clock jump -> no check
        assertFalse(shouldCheckNow(state = s, nowMs = NOW - 5_000L, visible = true))
    }

    @Test
    fun outdated_endsPolling() {
        assertFalse(shouldCheckNow(state = outdatedState(), nowMs = NOW + 10 * VERSION_CHECK_MIN_GAP_MS, visible = true))
    }

    @Test
    fun reloadButton_isWithdrawnAfterTwoAttempts() {
        var s = known()
        assertTrue(shouldShowReloadButton(s))
        s = onReloadRequested(s)
        assertEquals(1, s.reloadAttempts)
        assertTrue(shouldShowReloadButton(s))
        s = onReloadRequested(s)
        assertFalse(shouldShowReloadButton(s))
        s = onReloadRequested(s)
        assertFalse(shouldShowReloadButton(s))
    }

    @Test
    fun implausibleServerAnswer_isNotAVersion_andCountsAsFailure() {
        val bogus = listOf("<html>Bad Gateway</html>", "", "   ", "x".repeat(200))
        for (answer in bogus) {
            val s = onFetched(state = known(), serverBuildId = answer, nowMs = NOW)
            assertFalse(s.outdated, "answer=$answer")
            assertEquals(1, s.consecutiveFailures, "answer=$answer")
        }
    }

    @Test
    fun outdated_isTerminal_evenIfServerLaterMatchesAgain() {
        val back = onFetched(state = outdatedState(), serverBuildId = OWN, nowMs = NOW + 1)
        assertTrue(back.outdated)
    }

    @Test
    fun plausibleBuildId_shape() {
        assertTrue(isPlausibleBuildId(OWN))
        assertTrue(isPlausibleBuildId("v1_2-3"))
        assertFalse(isPlausibleBuildId(null))
        assertFalse(isPlausibleBuildId("a b"))
        assertFalse(isPlausibleBuildId("../x"))
    }

    @Test
    fun conferenceStates_countAsLiveCall_includingResolving() {
        assertTrue(ConferenceConnectionState.Connecting.countsAsLiveCall())
        assertTrue(ConferenceConnectionState.Connected.countsAsLiveCall())
        assertTrue(ConferenceConnectionState.Reconnecting.countsAsLiveCall())
        assertTrue(ConferenceConnectionState.Resolving.countsAsLiveCall())
        assertFalse(ConferenceConnectionState.Disconnected.countsAsLiveCall())
        assertFalse(ConferenceConnectionState.Ended.countsAsLiveCall())
        assertFalse(ConferenceConnectionState.Failed("x").countsAsLiveCall())
    }

    @Test
    fun effectiveReloadAttempts_resetsWhenBundleChangedSinceLastClick() {
        assertEquals(0, effectiveReloadAttempts(storedAttempts = 2, storedBuildId = "aaa", ownBuildId = "bbb"))
    }

    @Test
    fun effectiveReloadAttempts_keepsCounterWhenBundleUnchanged() {
        assertEquals(2, effectiveReloadAttempts(storedAttempts = 2, storedBuildId = "aaa", ownBuildId = "aaa"))
    }

    @Test
    fun effectiveReloadAttempts_keepsCounterWhenIdsUnknown() {
        assertEquals(1, effectiveReloadAttempts(storedAttempts = 1, storedBuildId = null, ownBuildId = "bbb"))
        assertEquals(1, effectiveReloadAttempts(storedAttempts = 1, storedBuildId = "aaa", ownBuildId = null))
    }
}
