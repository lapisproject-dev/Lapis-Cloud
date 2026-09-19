package network.lapis.cloud.client

import kotlin.math.min
import kotlin.math.roundToLong

/** Base polling interval of the "new version available" check (5 minutes), before jitter/backoff. */
internal const val VERSION_CHECK_BASE_DELAY_MS = 300_000L

/** Upper bound of the (backed-off) polling interval (30 minutes). */
internal const val VERSION_CHECK_MAX_DELAY_MS = 1_800_000L

/** Minimum gap between two version fetches, however they are triggered (tab re-focus included). */
internal const val VERSION_CHECK_MIN_GAP_MS = 60_000L

/** How long "Ausblenden" silences the banner (30 minutes, wall clock). */
internal const val VERSION_CHECK_SNOOZE_MS = 1_800_000L

/** After this many "Neu laden" clicks in one browser session the button is withdrawn (loop guard). */
internal const val VERSION_CHECK_MAX_RELOADS = 2

private const val MAX_BUILD_ID_LENGTH = 128
private const val MAX_BACKOFF_SHIFT = 3
private const val MIN_DELAY_MS = 1_000L
private const val JITTER_BASE_FACTOR = 0.8
private const val JITTER_SPREAD = 0.4

/**
 * Welle V1.4.20 "Client-Hinweis: Neue Version verfuegbar" -- the pure, browser-free state machine
 * behind `ClientVersionWatcher`. Every function is a total function of its arguments (no DOM, no
 * clock, no randomness of its own: `nowMs`/`jitter01` are passed in), so all of it is unit-testable
 * without a browser; see `ClientVersionCheckTest`.
 *
 * [outdated] is TERMINAL: once a build-id mismatch was seen it never goes back to `false` in this
 * page life -- the running bundle stays old however the server answers afterwards, and a server
 * that flips back (rollback) must not make the hint flicker away and reappear.
 */
internal data class VersionCheckState(
    /** The build id this tab was loaded with, or `null` when unknown (dev build / missing meta) -- then nothing is ever checked. */
    val ownBuildId: String? = null,
    val knownServerBuildId: String? = null,
    val outdated: Boolean = false,
    val snoozedUntilMs: Long? = null,
    val consecutiveFailures: Int = 0,
    val lastCheckAtMs: Long? = null,
    val reloadAttempts: Int = 0,
)

/**
 * Delay until the next poll: [baseMs] +/-20% jitter ([jitter01] clamped to 0..1, so `0.0` -> 80%,
 * `1.0` -> 120%), doubled per consecutive failure (at most 3 doublings), clamped to
 * 1 s..[VERSION_CHECK_MAX_DELAY_MS]. Jitter spreads a fleet of tabs that all opened together.
 */
internal fun nextDelayMs(
    state: VersionCheckState,
    jitter01: Double,
    baseMs: Long = VERSION_CHECK_BASE_DELAY_MS,
): Long {
    val factor = JITTER_BASE_FACTOR + JITTER_SPREAD * jitter01.coerceIn(0.0, 1.0)
    val jittered = (baseMs * factor).roundToLong()
    val shift = min(state.consecutiveFailures.coerceAtLeast(0), MAX_BACKOFF_SHIFT)
    return (jittered shl shift).coerceIn(MIN_DELAY_MS, VERSION_CHECK_MAX_DELAY_MS)
}

/**
 * `true` iff [value] plausibly is a build id: non-blank, at most 128 characters, only
 * `[0-9A-Za-z_-]`. A proxy / captive portal answering `200` with an HTML error page must NOT be
 * read as "a new version".
 */
internal fun isPlausibleBuildId(value: String?): Boolean =
    value != null &&
        value.isNotBlank() &&
        value.length <= MAX_BUILD_ID_LENGTH &&
        value.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it == '_' || it == '-' }

/**
 * Records a successful poll. An implausible answer ([isPlausibleBuildId]) counts as a failed poll
 * (the failure counter rises so a permanently broken proxy backs off). Otherwise the state becomes
 * `outdated` iff the server id differs from [VersionCheckState.ownBuildId]; already-`outdated`
 * stays `outdated` (terminal).
 */
internal fun onFetched(
    state: VersionCheckState,
    serverBuildId: String,
    nowMs: Long,
): VersionCheckState {
    if (!isPlausibleBuildId(serverBuildId)) return onFetchFailed(state = state, nowMs = nowMs)
    val own = state.ownBuildId
    return state.copy(
        knownServerBuildId = serverBuildId,
        outdated = state.outdated || (own != null && serverBuildId != own),
        consecutiveFailures = 0,
        lastCheckAtMs = nowMs,
    )
}

/** Records a failed poll: failure counter +1 (capped at 3), `lastCheckAtMs` updated, `outdated` untouched. */
internal fun onFetchFailed(
    state: VersionCheckState,
    nowMs: Long,
): VersionCheckState =
    state.copy(
        consecutiveFailures = (state.consecutiveFailures + 1).coerceAtMost(MAX_BACKOFF_SHIFT),
        lastCheckAtMs = nowMs,
    )

/**
 * Whether a poll may run now: only with a known own id, a visible tab, while not yet `outdated`
 * (polling ends with the first detection), and at least [minGapMs] after the previous poll. A
 * backwards clock jump makes the difference negative -> no poll; the next regular interval fixes it.
 */
internal fun shouldCheckNow(
    state: VersionCheckState,
    nowMs: Long,
    visible: Boolean,
    minGapMs: Long = VERSION_CHECK_MIN_GAP_MS,
): Boolean {
    val last = state.lastCheckAtMs
    return state.ownBuildId != null &&
        visible &&
        !state.outdated &&
        (last == null || nowMs - last >= minGapMs)
}

/** The banner shows iff outdated, no live call is running, and no snooze is active (snooze end is inclusive). */
internal fun shouldShowBanner(
    state: VersionCheckState,
    nowMs: Long,
    callLive: Boolean,
): Boolean {
    val snoozedUntil = state.snoozedUntilMs
    return state.outdated && !callLive && (snoozedUntil == null || nowMs >= snoozedUntil)
}

/** The "Neu laden" button is offered only while the reload budget of this browser session is not used up. */
internal fun shouldShowReloadButton(state: VersionCheckState): Boolean = state.reloadAttempts < VERSION_CHECK_MAX_RELOADS

/** "Ausblenden": silences the banner for [snoozeMs] of wall-clock time. */
internal fun onDismiss(
    state: VersionCheckState,
    nowMs: Long,
    snoozeMs: Long = VERSION_CHECK_SNOOZE_MS,
): VersionCheckState = state.copy(snoozedUntilMs = nowMs + snoozeMs)

/** A failed RPC while `outdated` is the moment the hint matters most: lifts any snooze. No-op otherwise. */
internal fun onRpcFailure(state: VersionCheckState): VersionCheckState = if (state.outdated) state.copy(snoozedUntilMs = null) else state

/** Counts one "Neu laden" click (persisted by the adapter in `sessionStorage`). */
internal fun onReloadRequested(state: VersionCheckState): VersionCheckState = state.copy(reloadAttempts = state.reloadAttempts + 1)

/**
 * The reload budget that is still valid in this tab: the stored click counter, reset to `0` when the
 * bundle changed since the last click ([storedBuildId] = the own build id at that click differs from
 * the current [ownBuildId]) -- that reload demonstrably worked, so it must not count against the
 * loop guard, which exists only for reloads that do NOT bring a newer bundle.
 */
internal fun effectiveReloadAttempts(
    storedAttempts: Int,
    storedBuildId: String?,
    ownBuildId: String?,
): Int = if (storedBuildId != null && ownBuildId != null && storedBuildId != ownBuildId) 0 else storedAttempts
