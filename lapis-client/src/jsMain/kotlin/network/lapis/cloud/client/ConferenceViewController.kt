package network.lapis.cloud.client

import network.lapis.cloud.shared.conference.ConferenceTileRef
import network.lapis.cloud.shared.conference.ConferenceView
import network.lapis.cloud.shared.conference.ConferenceViewEvent
import network.lapis.cloud.shared.conference.ConferenceViewInput
import network.lapis.cloud.shared.conference.ConferenceViewLayout
import network.lapis.cloud.shared.conference.ConferenceViewParticipant
import network.lapis.cloud.shared.conference.ConferenceViewScreenShare
import network.lapis.cloud.shared.conference.ConferenceViewState
import network.lapis.cloud.shared.conference.SpeakerStageSmoother
import network.lapis.cloud.shared.conference.computeConferenceViewLayout
import network.lapis.cloud.shared.conference.conferenceGalleryPageSize
import network.lapis.cloud.shared.conference.conferenceViewFromStorage
import network.lapis.cloud.shared.conference.conferenceViewStorageValue
import network.lapis.cloud.shared.conference.initialConferenceView
import network.lapis.cloud.shared.conference.reduceConferenceView

/*
 * V1.9.115 -- the state of the switchable views of the live conference, without any DOM (testable in jsTest). It owns what the pure
 * `lapis-shared` functions need to be fed: the view state machine, the speaker smoothing, the join order of the people, the current page
 * of the gallery and of the filmstrip, and the raw "is speaking" set.
 *
 * Only the choice GALLERY / SPEAKER is ever persisted ([ConferenceViewStore]); a person (the pinned one) never is. The time is always
 * passed in (`nowMs`), the controller reads no clock.
 */

/** Something the view announces to assistive technology (the screen turns it into a live-region text). */
internal sealed interface ConferenceViewNotice {
    /** The pinned person left: the view is "speaker large" now. */
    data object PinnedLeft : ConferenceViewNotice

    /** The view changed by itself (a share started or ended): the new view is announced. */
    data class ViewChanged(
        val view: ConferenceView,
    ) : ConferenceViewNotice
}

internal class ConferenceViewController(
    private val selfId: String,
    storedView: ConferenceView = ConferenceView.GALLERY,
    shareActive: Boolean = false,
) {
    var state: ConferenceViewState = initialConferenceView(storedView, shareActive)
        private set

    private val smoother = SpeakerStageSmoother()
    private val joinOrder = LinkedHashMap<String, Long>()
    private var joinCounter = 0L
    private var speakingByLevel: List<String> = emptyList()
    private var lastExternalSpeaker: String? = null
    private var shareIdentity: String? = null
    private var shareStartOrder = 0L
    private var shareOrderCounter = 0L

    /** Current gallery page (clamped on every layout) and current filmstrip page. */
    var page: Int = 0
        private set
    var stripPage: Int = 0
        private set

    private var pendingNotice: ConferenceViewNotice? = null

    /** The notice the last change produced, once (the screen announces it). */
    fun takeNotice(): ConferenceViewNotice? = pendingNotice.also { pendingNotice = null }

    // -- people ---------------------------------------------------------------------------------------------------------------

    fun participantJoined(identity: String) {
        if (identity !in joinOrder) joinOrder[identity] = joinCounter++
    }

    /** [identity] left. Returns `true` when that was the pinned person (the view falls back by itself on the next layout). */
    fun participantLeft(identity: String): Boolean {
        joinOrder.remove(identity)
        smoother.forget(identity)
        if (lastExternalSpeaker == identity) lastExternalSpeaker = null
        speakingByLevel = speakingByLevel.filter { it != identity }
        if (shareIdentity == identity) shareEnded()
        return state.pinnedId == identity
    }

    // -- speakers -------------------------------------------------------------------------------------------------------------

    /** A report of LiveKit: [identitiesByLevel] is the complete current set of speakers, loudest first. */
    fun onSpeakers(
        nowMs: Long,
        identitiesByLevel: List<String>,
    ) {
        speakingByLevel = identitiesByLevel
        identitiesByLevel.firstOrNull { it != selfId }?.let { lastExternalSpeaker = it }
        smoother.observe(nowMs, identitiesByLevel.filter { it != selfId })
    }

    /** The beat (only while the view needs it): lets a claim mature although LiveKit reports nothing new. */
    fun tick(nowMs: Long) {
        smoother.observe(nowMs, speakingByLevel.filter { it != selfId })
    }

    /** Whether the beat has to keep running: somebody speaks and the view follows the speaker. */
    fun needsBeat(): Boolean =
        speakingByLevel.any { it != selfId } && (state.chosen == ConferenceView.SPEAKER || state.chosen == ConferenceView.PINNED)

    // -- user and share events ------------------------------------------------------------------------------------------------

    private fun firstOther(): String? = joinOrder.keys.firstOrNull { it != selfId }

    private fun reduce(event: ConferenceViewEvent): Boolean {
        val next = reduceConferenceView(state, event, selfId, lastExternalSpeaker, firstOther())
        val changed = next != state
        state = next
        if (changed) stripPage = 0
        return changed
    }

    /** The user picked [view] in the switcher. Returns whether anything changed. */
    fun choose(view: ConferenceView): Boolean = reduce(ConferenceViewEvent.UserChose(view))

    fun pin(identity: String): Boolean = reduce(ConferenceViewEvent.Pin(identity))

    fun unpin(): Boolean = reduce(ConferenceViewEvent.Unpin)

    /** A tile of the filmstrip was activated: that person becomes the pinned one. */
    fun activateStripTile(identity: String): Boolean = reduce(ConferenceViewEvent.StripTileActivated(identity))

    /** The (only) screen share of the call started: the view switches to it, the earlier choice is remembered. */
    fun shareStarted(identity: String) {
        shareIdentity = identity
        shareStartOrder = shareOrderCounter++
        val before = state.chosen
        reduce(ConferenceViewEvent.ShareStarted)
        if (before != ConferenceView.SCREEN_SHARE) pendingNotice = ConferenceViewNotice.ViewChanged(ConferenceView.SCREEN_SHARE)
    }

    fun shareEnded() {
        if (shareIdentity == null) return
        shareIdentity = null
        val before = state.chosen
        reduce(ConferenceViewEvent.AllSharesEnded)
        if (before == ConferenceView.SCREEN_SHARE) pendingNotice = ConferenceViewNotice.ViewChanged(state.chosen)
    }

    fun shareActive(): Boolean = shareIdentity != null

    /** Browser storage value of the current user choice (`null` for a view that is never stored). */
    fun storageValue(): String? = conferenceViewStorageValue(state.chosen)

    // -- paging ---------------------------------------------------------------------------------------------------------------

    fun turnPage(delta: Int) {
        page = (page + delta).coerceAtLeast(0)
    }

    fun goToPage(target: Int) {
        page = target.coerceAtLeast(0)
    }

    fun turnStripPage(delta: Int) {
        stripPage = (stripPage + delta).coerceAtLeast(0)
    }

    /** Stores the filmstrip page the screen clamped against what really fits. */
    fun setStripPage(clamped: Int) {
        stripPage = clamped.coerceAtLeast(0)
    }

    // -- layout ---------------------------------------------------------------------------------------------------------------

    /**
     * The layout for the current state. [hasVideo] maps every person who is in the room (self included) to whether a camera picture is
     * shown for them. A person the controller has not been told about yet is registered here (joins are never missed). The page is
     * clamped and remembered; a pinned person who left turns the view into "speaker large" and queues a notice.
     */
    fun layout(
        hasVideo: Map<String, Boolean>,
        areaWidthPx: Double,
        nowMs: Long? = null,
    ): ConferenceViewLayout {
        hasVideo.keys.forEach { participantJoined(it) }
        joinOrder.keys.retainAll(hasVideo.keys)
        nowMs?.let { tick(it) }
        val participants =
            hasVideo.map { (identity, video) -> ConferenceViewParticipant(identity, joinOrder.getValue(identity), video) }
        val shares =
            shareIdentity?.takeIf { it in hasVideo }?.let { listOf(ConferenceViewScreenShare(it, shareStartOrder)) }.orEmpty()
        val result =
            computeConferenceViewLayout(
                ConferenceViewInput(
                    view = state.chosen,
                    participants = participants,
                    selfId = selfId,
                    mainSpeakerId = smoother.current()?.takeIf { it != selfId },
                    speakingIds = speakingByLevel.toSet(),
                    pinnedId = state.pinnedId,
                    screenShares = shares,
                    fallbackView = state.beforeShare?.first ?: ConferenceView.GALLERY,
                    page = page,
                    pageSize = conferenceGalleryPageSize(areaWidthPx),
                ),
            )
        page = result.page
        if (result.pinnedLeft && state.chosen == ConferenceView.PINNED) {
            reduce(ConferenceViewEvent.PinnedLeft)
            pendingNotice = ConferenceViewNotice.PinnedLeft
        }
        return result
    }

    /** The `key` of the tile the floating window should show as its big picture (`null` in the gallery: the speaker rule of the window applies). */
    fun floatPreferredKey(layout: ConferenceViewLayout): String? =
        if (layout.effectiveView == ConferenceView.GALLERY) null else layout.main?.let(::floatKeyOf)

    companion object {
        fun floatKeyOf(tile: ConferenceTileRef): String = if (tile.screenShare) "share:${tile.identity}" else "tile:${tile.identity}"

        /** The view stored in the browser, or the gallery. */
        fun fromStorage(raw: String?): ConferenceView = conferenceViewFromStorage(raw)
    }
}

/**
 * The part of a filmstrip of [strip] tiles that fits [capacity] tiles on [page] (clamped), and the clamped page and the page count.
 * A capacity below 1 is treated as 1. Pure.
 */
internal fun conferenceStripSlice(
    strip: List<ConferenceTileRef>,
    capacity: Int,
    page: Int,
): Triple<List<ConferenceTileRef>, Int, Int> {
    val size = capacity.coerceAtLeast(1)
    val count = maxOf(1, (strip.size + size - 1) / size)
    val clamped = page.coerceIn(0, count - 1)
    val from = (clamped * size).coerceAtMost(strip.size)
    return Triple(strip.subList(from, (from + size).coerceAtMost(strip.size)), clamped, count)
}
