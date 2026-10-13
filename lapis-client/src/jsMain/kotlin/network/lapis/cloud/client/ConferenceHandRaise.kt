package network.lapis.cloud.client

import io.kvision.i18n.gettext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.lapis.cloud.client.livekit.ConferenceHandQueueView
import network.lapis.cloud.client.livekit.HandAnnouncementThrottle
import network.lapis.cloud.client.livekit.SelfHandAction
import network.lapis.cloud.shared.domain.ConferenceHandQueueDto

/*
 * V1.9.127 -- the raised-hand ("Wortmeldung") logic of the call screen, free of DOM and RPC types so a test can drive it with a fake
 * gateway: what the person believes about their own hand, the epoch rule that decides "a moderator lowered it" versus "the server restarted",
 * and the moderators' (throttled) announcements. The screen owns the widgets; it only reads [ConferenceHandController.order] & co. after
 * `onChanged`. It contains NO `rpcService` read (the ledger of `ClientDataStateTripwireTest` must not grow): the gateway is built in
 * `ConferenceScreen.kt`.
 */

/** The five calls of `IConferenceHandService` as the controller needs them. `null` = the call failed (the gateway already told the person). */
internal interface ConferenceHandGateway {
    suspend fun raise(): ConferenceHandQueueDto?

    suspend fun lower(): ConferenceHandQueueDto?

    suspend fun queue(): ConferenceHandQueueDto?

    suspend fun lowerOf(identity: String): ConferenceHandQueueDto?

    suspend fun lowerAll(): ConferenceHandQueueDto?
}

/** A call whose failure must not toast (a periodic read, the own raise whose failure the controller reports in its own words): `null` on failure. */
internal suspend fun <T> quietly(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

/**
 * Pure: the mini bar's / floating window's words about the raised hands -- the own hand first, then how many hands are up in the room. No
 * names, no position (those stay in the view); the same text serves the badge and the accessible name.
 */
internal fun conferenceHandBadges(
    handRaisedSelf: Boolean,
    raisedHandsCount: Int?,
): List<String> =
    buildList {
        if (handRaisedSelf) add(gettext("Ihre Hand ist gehoben"))
        if (raisedHandsCount != null && raisedHandsCount > 0) add(gettext("Gehobene Hände: %1", raisedHandsCount))
    }

/** Interval of the periodic read of the queue while the own hand is up or the roster is open (the broadcast can get lost). */
internal const val CONFERENCE_HAND_RESYNC_MS = 60_000

internal class ConferenceHandController(
    private val self: String,
    private val gateway: ConferenceHandGateway,
    private val scope: CoroutineScope,
    /** Everything that is drawn from the state is redrawn (bar button, tile badges, roster, panel, dock). */
    private val onChanged: () -> Unit,
    /** Only the own person hears it (a status line, not shown to others). */
    private val announceSelf: (String) -> Unit,
    /** The polite live region of the moderators. */
    private val announceModerators: (String) -> Unit,
    private val notifyError: (String) -> Unit,
    private val isModerator: Boolean,
    private val announceEnabled: () -> Boolean,
    private val nameOf: (String) -> String,
    private val throttle: HandAnnouncementThrottle,
) {
    private val view = ConferenceHandQueueView(self)
    private var believedRaised = false
    private var raisedInEpoch: String? = null
    private var flushJob: Job? = null
    private var raising = false

    /**
     * `true` while the own hand is being lowered by the own request ([lowerSelf], or [lowerAll] with the own hand up). The server sends the
     * broadcast BEFORE it answers the request, so the snapshot without the own identity usually arrives first -- that is no "lowered by
     * somebody else", and must not be announced as such.
     */
    private var lowerOwnPending = false

    /** `true` while a raise/lower of the own hand is in flight (the button waits). */
    var busy: Boolean = false
        private set

    val order: List<String> get() = view.order
    val selfRaised: Boolean get() = believedRaised
    val selfPosition: Int? get() = view.positionOfSelf()

    /** The bar's toggle: raises the own hand, or lowers it again. */
    suspend fun toggleSelf() {
        if (busy) return
        busy = true
        onChanged()
        try {
            if (believedRaised) lowerSelf() else raiseSelf()
        } finally {
            busy = false
            onChanged()
        }
    }

    private suspend fun raiseSelf() {
        val dto = gateway.raise()
        if (dto == null) {
            notifyError(gettext("Die Hand konnte nicht gehoben werden. Bitte versuchen Sie es erneut."))
            return
        }
        believedRaised = true
        raisedInEpoch = dto.epoch
        apply(dto)
    }

    private suspend fun lowerSelf() {
        lowerOwnPending = true
        try {
            val dto = gateway.lower() ?: return
            believedRaised = false
            raisedInEpoch = null
            apply(dto)
        } finally {
            lowerOwnPending = false
        }
    }

    /** A moderator lowers one hand. The own hand goes through the own toggle path (no "was lowered" notice for oneself). */
    suspend fun lowerOf(identity: String) {
        if (!isModerator) return
        if (identity == self) {
            lowerSelf()
            return
        }
        gateway.lowerOf(identity)?.let { apply(it) }
    }

    suspend fun lowerAll() {
        if (!isModerator) return
        val wasRaised = believedRaised
        lowerOwnPending = wasRaised
        try {
            val dto = gateway.lowerAll() ?: return
            // the moderator who clears the list lowered their own hand as well: no "your hand was lowered" notice for that
            if (wasRaised) {
                believedRaised = false
                raisedInEpoch = null
            }
            apply(dto)
        } finally {
            lowerOwnPending = false
        }
    }

    /** The periodic / on-join read; keeps the picture honest if a broadcast was lost. */
    suspend fun resync() {
        gateway.queue()?.let { apply(it) }
    }

    /** A broadcast of the server (already validated by `conferenceHandsPacket`). */
    fun onBroadcast(dto: ConferenceHandQueueDto) {
        apply(dto)
    }

    private fun apply(dto: ConferenceHandQueueDto) {
        val firstOfEpoch = view.epoch != dto.epoch
        val before = view.order
        if (!view.accept(dto)) return
        if (!firstOfEpoch && isModerator && announceEnabled()) announceNewHands(before)
        when (view.reconcileSelf(believedRaised = believedRaised, raisedInEpoch = raisedInEpoch)) {
            SelfHandAction.RAISE_AGAIN -> reRaise()
            SelfHandAction.MARK_LOWERED_BY_OTHERS ->
                // the own lowering is in flight: its answer settles the state, nobody else lowered the hand
                if (!lowerOwnPending) {
                    believedRaised = false
                    raisedInEpoch = null
                    announceSelf(gettext("Ihre Hand wurde gesenkt."))
                }
            SelfHandAction.NONE ->
                // the hand is up (raised in another tab, or before a reload): adopt it, so the button tells the truth
                if (!believedRaised && view.positionOfSelf() != null) {
                    believedRaised = true
                    raisedInEpoch = dto.epoch
                }
        }
        onChanged()
    }

    /** The server restarted (new epoch): the hand is raised again, at the end of the list. Silent on failure -- the next read retries. */
    private fun reRaise() {
        if (raising) return
        raising = true
        scope.launch {
            try {
                val dto = gateway.raise()
                if (dto != null) {
                    raisedInEpoch = dto.epoch
                    apply(dto)
                }
            } finally {
                raising = false
            }
        }
    }

    private fun announceNewHands(before: List<String>) {
        val fresh = view.order.filter { it !in before && it != self }.map { nameOf(it) }
        val text = throttle.onRaised(fresh)
        if (text != null) {
            announceModerators(text)
        } else if (fresh.isNotEmpty()) {
            scheduleFlush()
        }
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        val wait = throttle.delayUntilFlushMs() ?: return
        flushJob =
            scope.launch {
                delay(wait.toLong() + 1)
                throttle.flush()?.let { announceModerators(it) }
            }
    }

    /** Called when the call ends: nothing may announce afterwards. */
    fun dispose() {
        flushJob?.cancel()
        throttle.clear()
    }
}
