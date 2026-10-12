package network.lapis.cloud.shared.conference

/**
 * V1.9.115 -- the smoothing of "who is the large tile" shared by the LIVE view (`lapis-client`) and the recording composition
 * (`lapis-server`): pure, deterministic, no clock of its own (the caller always passes `nowMs`), no I/O.
 *
 * The rules (one place, so the live call and the recorded video cannot disagree):
 *  - a candidate takes over only after [SPEAKER_CLAIM_MS] of uninterrupted speech (a hole of at most [SPEAKER_SAMPLE_TOLERANCE_MS],
 *    one missed sample, is not an interruption);
 *  - the large tile changes at the earliest [SPEAKER_MIN_DWELL_MS] after it last changed;
 *  - a pause of the current speaker of up to [SPEAKER_GAP_MERGE_MS] is bridged (nobody else takes over inside it);
 *  - in silence the large tile stays where it is.
 */
const val SPEAKER_MIN_DWELL_MS = 2_000L
const val SPEAKER_CLAIM_MS = 800L
const val SPEAKER_GAP_MERGE_MS = 1_500L

/**
 * Two observations of a candidate speaking at most this far apart count as uninterrupted speech: one missed sample of the 100 ms grid of
 * the recording analysis, with slack for the 250 ms tick of the live view. A longer hole restarts the claim.
 */
const val SPEAKER_SAMPLE_TOLERANCE_MS = 300L

/**
 * The incremental form. [observe] is fed the people currently speaking (loudest first) and answers the person who is the large tile.
 * Not thread-safe; one instance per room view.
 */
class SpeakerStageSmoother(
    private val claimMs: Long = SPEAKER_CLAIM_MS,
    private val dwellMs: Long = SPEAKER_MIN_DWELL_MS,
    private val gapMergeMs: Long = SPEAKER_GAP_MERGE_MS,
    private val sampleToleranceMs: Long = SPEAKER_SAMPLE_TOLERANCE_MS,
) {
    private var main: String? = null
    private var mainSinceMs: Long = 0L
    private var mainLastSpokeMs: Long = 0L

    /** The main speaker left: the next claim may take over at once (no dwell, no gap to bridge). */
    private var mainGone = false
    private var candidate: String? = null
    private var candidateSinceMs: Long = 0L
    private var candidateLastSeenMs: Long = 0L

    /** The person who is the large tile right now, or `null` while nobody has spoken yet. */
    fun current(): String? = main

    /**
     * Feeds one observation. [speakingByLevel] is everybody who is speaking at [nowMs], loudest first (LiveKit order / level order).
     * Returns the main speaker after this observation.
     */
    fun observe(
        nowMs: Long,
        speakingByLevel: List<String>,
    ): String? {
        val current = main
        if (current != null && current in speakingByLevel) {
            mainLastSpokeMs = nowMs
            mainGone = false
        }
        // The challenger: the loudest speaker that is not the current main speaker.
        val challenger = speakingByLevel.firstOrNull { it != current }
        if (challenger == null) {
            // Nobody else speaks. A hole in the candidate's speech longer than the tolerance resets its claim.
            if (candidate != null && nowMs - candidateLastSeenMs > sampleToleranceMs) candidate = null
            return main
        }
        if (candidate == challenger && nowMs - candidateLastSeenMs <= sampleToleranceMs) {
            candidateLastSeenMs = nowMs
        } else if (candidate != challenger || nowMs - candidateLastSeenMs > sampleToleranceMs) {
            candidate = challenger
            candidateSinceMs = nowMs
            candidateLastSeenMs = nowMs
        }
        val claimed = nowMs - candidateSinceMs >= claimMs
        val mayTakeOver =
            current == null ||
                mainGone ||
                (nowMs - mainLastSpokeMs > gapMergeMs && nowMs - mainSinceMs >= dwellMs)
        if (claimed && mayTakeOver) {
            main = challenger
            mainSinceMs = nowMs
            mainLastSpokeMs = nowMs
            mainGone = false
            candidate = null
        }
        return main
    }

    /** [identity] left the room. If it was the main speaker it stays the answer until the next claim (which then takes over at once). */
    fun forget(identity: String) {
        if (candidate == identity) candidate = null
        if (main == identity) mainGone = true
    }
}

/** A person (opaque [key]) was the large tile from [startMs] to [endMs] (exclusive) of a timeline. */
data class SpeakerSpan(
    val key: String,
    val startMs: Long,
    val endMs: Long,
)

/** One observation of the batch form: everybody speaking at [atMs], loudest first. */
data class SpeechSample(
    val atMs: Long,
    val speakingByLevel: List<String>,
)

/**
 * The batch form for the server: runs [SpeakerStageSmoother] over [samples] (any order, sorted here) and answers the smoothed spans.
 * Spans are adjacent without overlap, the first one starts when the first person took over (before it the picture is the grid), the last
 * one ends at [totalMs]. Zero-length spans are dropped. The result is exactly what feeding the same samples to the incremental form
 * would have shown.
 */
fun smoothSpeakerTimeline(
    samples: List<SpeechSample>,
    totalMs: Long,
    claimMs: Long = SPEAKER_CLAIM_MS,
    dwellMs: Long = SPEAKER_MIN_DWELL_MS,
    gapMergeMs: Long = SPEAKER_GAP_MERGE_MS,
): List<SpeakerSpan> {
    val smoother = SpeakerStageSmoother(claimMs = claimMs, dwellMs = dwellMs, gapMergeMs = gapMergeMs)
    val spans = mutableListOf<SpeakerSpan>()
    var openKey: String? = null
    var openStart = 0L
    for (sample in samples.sortedBy { it.atMs }) {
        val now = smoother.observe(nowMs = sample.atMs, speakingByLevel = sample.speakingByLevel)
        if (now != openKey) {
            val key = openKey
            if (key != null && sample.atMs > openStart) spans += SpeakerSpan(key = key, startMs = openStart, endMs = sample.atMs)
            openKey = now
            openStart = sample.atMs
        }
    }
    val key = openKey
    if (key != null && totalMs > openStart) spans += SpeakerSpan(key = key, startMs = openStart, endMs = totalMs)
    return spans
}
