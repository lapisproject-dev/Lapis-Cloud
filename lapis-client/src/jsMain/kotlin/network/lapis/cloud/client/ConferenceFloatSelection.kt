package network.lapis.cloud.client

import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement

/*
 * V1.9.71 -- what the floating window shows: which source is the big picture, which ones go into the strip, how many are folded into "+N",
 * and which video quality each remote picture needs. Pure: the DOM-free part is [floatSelectionOf] / [floatQualityPlan], the one DOM-typed
 * value is [FloatMediaSource] (handed over by the call view as ready-made strings, so this file never sees a name or an identity).
 */

/** A source of the call as the floating window sees it. [key] is opaque, [label] is the finished tile text (shown by `textContent` only). */
internal data class FloatMediaSource(
    val key: String,
    val label: String,
    val isLocal: Boolean,
    /** Only foreign screen shares; the own share is never shown as a picture. */
    val isScreenShare: Boolean,
    /** `null` = a name tile (no picture). */
    val video: HTMLVideoElement?,
    /** Where the element lives when it is not on loan (the tile's media slot, or the share stage). */
    val home: HTMLElement,
    val lastSpokeAtMs: Long,
    /** `null` for the own picture and for shares. */
    val setQuality: ((Int) -> Unit)?,
)

/** The DOM-free view of a [FloatMediaSource] for the selection. */
internal data class FloatSourceInfo(
    val key: String,
    val isLocal: Boolean,
    val isScreenShare: Boolean,
    val hasVideo: Boolean,
    val lastSpokeAtMs: Long,
)

internal fun FloatMediaSource.info(): FloatSourceInfo = FloatSourceInfo(key, isLocal, isScreenShare, video != null, lastSpokeAtMs)

internal data class FloatSelection(
    val mainKey: String?,
    val stripKeys: List<String>,
    val insetKey: String?,
    val hiddenCount: Int,
)

/**
 * The speaker hysteresis: [mainKey] is what is shown as the big picture, [candidateKey] a different leading speaker since [since]. A
 * candidate only takes over after [FLOAT_SPEAKER_HYSTERESIS_MS] of uninterrupted leading, so a short interjection does not flip the picture.
 */
internal data class FloatSpeakerMemo(
    val mainKey: String?,
    val since: Long,
    val candidateKey: String? = null,
)

internal const val FLOAT_SPEAKER_HYSTERESIS_MS = 2_000L

/** An identity counts as "speaking now" while its last active-speaker push is at most this old. */
internal const val FLOAT_SPEAKING_WINDOW_MS = 1_500L

private const val FLOAT_STRIP_SLOTS = 3

/**
 * Pure. Big picture, in order: (1) a foreign screen share; (2) the person who is speaking (changes after the hysteresis); (3) the first
 * remote person; (4) alone: the own picture (only with the camera on). The strip (medium and large) holds up to three more; the own picture
 * takes one of those places and only exists with [cameraOn] and a real video. Small has no strip: the own picture is an inset, the rest
 * counts in [FloatSelection.hiddenCount].
 */
internal fun floatSelectionOf(
    sources: List<FloatSourceInfo>,
    size: FloatSize,
    cameraOn: Boolean,
    nowMs: Long,
    memo: FloatSpeakerMemo,
): Pair<FloatSelection, FloatSpeakerMemo> {
    val remotes = sources.filter { !it.isLocal && !it.isScreenShare }
    val share = sources.firstOrNull { it.isScreenShare && !it.isLocal }
    val self = sources.firstOrNull { it.isLocal && !it.isScreenShare && it.hasVideo && cameraOn }

    var newMemo = memo
    val mainKey: String? =
        when {
            share != null -> {
                newMemo = FloatSpeakerMemo(mainKey = memo.mainKey?.takeIf { key -> remotes.any { it.key == key } }, since = nowMs)
                share.key
            }
            remotes.isEmpty() -> {
                newMemo = FloatSpeakerMemo(mainKey = null, since = nowMs)
                self?.key
            }
            else -> {
                val shown = memo.mainKey?.takeIf { key -> remotes.any { it.key == key } }
                val leader =
                    remotes
                        .filter { it.lastSpokeAtMs > 0 && nowMs - it.lastSpokeAtMs <= FLOAT_SPEAKING_WINDOW_MS }
                        .maxByOrNull { it.lastSpokeAtMs }
                        ?.key
                when {
                    shown == null -> {
                        val pick = leader ?: (remotes.firstOrNull { it.hasVideo } ?: remotes.first()).key
                        newMemo = FloatSpeakerMemo(mainKey = pick, since = nowMs)
                        pick
                    }
                    leader == null || leader == shown -> {
                        newMemo = FloatSpeakerMemo(mainKey = shown, since = nowMs)
                        shown
                    }
                    memo.candidateKey != leader -> {
                        // a different leader was just noticed: its clock starts now
                        newMemo = FloatSpeakerMemo(mainKey = shown, since = nowMs, candidateKey = leader)
                        shown
                    }
                    nowMs - memo.since >= FLOAT_SPEAKER_HYSTERESIS_MS -> {
                        newMemo = FloatSpeakerMemo(mainKey = leader, since = nowMs)
                        leader
                    }
                    else -> {
                        newMemo = memo
                        shown
                    }
                }
            }
        }

    val otherRemotes =
        remotes
            .filter { it.key != mainKey }
            .sortedWith(compareByDescending<FloatSourceInfo> { it.hasVideo }.thenByDescending { it.lastSpokeAtMs })
    val selfIsExtra = self != null && self.key != mainKey
    return when (size) {
        FloatSize.SMALL ->
            FloatSelection(
                mainKey = mainKey,
                stripKeys = emptyList(),
                insetKey = if (selfIsExtra) self?.key else null,
                hiddenCount = otherRemotes.size,
            ) to newMemo
        FloatSize.MEDIUM, FloatSize.LARGE -> {
            val remoteSlots = if (selfIsExtra) FLOAT_STRIP_SLOTS - 1 else FLOAT_STRIP_SLOTS
            val shownRemotes = otherRemotes.take(remoteSlots)
            val strip = shownRemotes.map { it.key } + (if (selfIsExtra) listOfNotNull(self?.key) else emptyList())
            FloatSelection(
                mainKey = mainKey,
                stripKeys = strip,
                insetKey = null,
                hiddenCount = otherRemotes.size - shownRemotes.size,
            ) to newMemo
        }
    }
}

/**
 * Pure: the requested quality (0 = low, 1 = medium, 2 = high) of every remote picture. Full view: everything high. Floating: the big
 * picture medium, strip and folded pictures low. Bar: nothing is visible, everything low.
 */
internal fun floatQualityPlan(
    selection: FloatSelection?,
    remoteKeys: Collection<String>,
    presentation: DockPresentation,
): Map<String, Int> =
    remoteKeys.associateWith { key ->
        when (presentation) {
            DockPresentation.FULL -> 2
            DockPresentation.BAR -> 0
            DockPresentation.FLOAT -> if (selection?.mainKey == key) 1 else 0
        }
    }
