package network.lapis.cloud.client

import io.kvision.i18n.gettext
import io.kvision.panel.SimplePanel
import kotlinx.coroutines.CancellationException
import network.lapis.cloud.shared.domain.SystemicConsensusDto
import network.lapis.cloud.shared.domain.SystemicConsensusResultDto
import network.lapis.cloud.shared.rpc.ISystemicConsensusService

/*
 * V1.9.32 "Konsensieren im Konferenzraum" -- everything the room panel needs to embed the resistance booth, kept out of
 * `ConferenceVotePanel.kt` so that file stays a thin wiring layer: the RPC seam, the narrow-panel rule, the receipt hook scope, the stream
 * lock of an anonymous consensus and the decision "booth, new tab or nothing" before the booth opens.
 *
 * A rating or a receipt code never passes through this file: the booth (`ConsensusBooth.kt`) is the only place either exists. The receipt
 * hook scope carries one Boolean ("a receipt is on screen"), the lock hook carries a reason text.
 */

/**
 * Every RPC of the room's consensus side behind ONE seam: the DOM tests run against the fetch stub, a unit test can pass fakes. The four
 * writes are operator steps and are only ever called through `ConferenceConsensusOperator`'s guarded action.
 */
internal class ConferenceConsensusRpc(
    /** The snapshot of one consensus for the panel: reads quietly, `null` when it cannot be read. */
    val loadDetail: suspend (String) -> ConsensusDetailData? = { loadConsensusDetailQuietly(it) },
    val freeze: suspend (String) -> Unit = { rpcService<ISystemicConsensusService>().freezeOptions(it) },
    val closeRating: suspend (String) -> Unit = { rpcService<ISystemicConsensusService>().closeRating(it) },
    val evaluate: suspend (String) -> SystemicConsensusResultDto = { rpcService<ISystemicConsensusService>().evaluate(it) },
    val reopen: suspend (String) -> Unit = { rpcService<ISystemicConsensusService>().reopenRating(it) },
)

/** 11 radio fields of 24 px with gaps, plus the panel's padding: below this the compact grid no longer fits in one row. */
internal const val CONSENSUS_COMPACT_MIN_WIDTH_PX = 290

internal fun consensusFitsPanel(widthPx: Int): Boolean = widthPx >= CONSENSUS_COMPACT_MIN_WIDTH_PX

/** What happens when a member clicks "Bewerten" -- decided from a FRESH read, never from the card. */
internal sealed interface ConsensusEntry {
    /** Open the embedded booth for this consensus. */
    data class Booth(
        val consensus: SystemicConsensusDto,
    ) : ConsensusEntry

    /** The panel is too narrow for the booth: offer the booth in its own tab. */
    data class NarrowTab(
        val href: String,
    ) : ConsensusEntry

    /** The consensus is no longer in RATING, or the member can no longer rate. */
    data object NotOpen : ConsensusEntry

    /** The consensus could not be read. */
    data object LoadFailed : ConsensusEntry
}

/**
 * The booth only opens for a consensus that is RATING and that the server still lets this member rate (`canRate`). The width is measured
 * only now -- the panel is visible at the moment of the click -- and a changed width later never re-renders the booth.
 */
internal suspend fun decideConsensusEntry(
    rpc: ConferenceConsensusRpc,
    ballotId: String,
    measureWidth: () -> Int,
): ConsensusEntry {
    val detail =
        try {
            rpc.loadDetail(ballotId)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Throwable) {
            null
        } ?: return ConsensusEntry.LoadFailed
    if (!canEnterConsensusBooth(detail.consensus, detail.participation)) return ConsensusEntry.NotOpen
    if (consensusFitsPanel(measureWidth())) return ConsensusEntry.Booth(detail.consensus)
    val href = conferenceConsensusDetailHref(detail.consensus.id) ?: return ConsensusEntry.NotOpen
    return ConsensusEntry.NarrowTab(href)
}

/** Embeds the resistance booth into [boothArea]. [ballotLock] is `null` for an open consensus (it never pauses the stream). */
internal fun renderConsensusBoothInRoom(
    boothArea: SimplePanel,
    consensus: SystemicConsensusDto,
    exitLabel: String,
    ballotLock: BallotLockHook?,
    onBusyChanged: (Boolean) -> Unit,
    onExit: (refresh: Boolean) -> Unit,
) {
    renderConsensusBooth(
        panel = boothArea,
        consensus = consensus,
        onExit = onExit,
        roomHost =
            ConsensusBoothRoomHost(
                exitLabel = exitLabel,
                ballotLock = if (consensus.secret) ballotLock else null,
                onBusyChanged = onBusyChanged,
                compact = true,
            ),
    )
}

/**
 * Takes `consensusReceiptVisibilityHook` for the life of the panel and gives it back -- the twin of [ConferenceReceiptHookScope] for the
 * elections. The hook is one global variable that the consensus screen also sets; a panel that simply overwrote it would leave its own
 * closure behind. The identity check keeps a hook somebody else set in the meantime.
 */
internal class ConferenceConsensusReceiptHookScope(
    private val onChange: (Boolean) -> Unit,
) {
    private var previous: ((Boolean) -> Unit)? = null
    private var installed = false

    fun install() {
        if (installed) return
        previous = consensusReceiptVisibilityHook
        consensusReceiptVisibilityHook = onChange
        installed = true
    }

    fun restore() {
        if (installed && consensusReceiptVisibilityHook === onChange) consensusReceiptVisibilityHook = previous
        installed = false
        previous = null
    }
}

/**
 * The lock of an ANONYMOUS consensus as the booth sees it: while the live stream is still being paused the final submit is disabled, with a
 * reason in plain words (what is happening, why, until when). Members read HUNG as "slow"; the stream status comes from the panel's clock.
 */
internal fun consensusMemberBallotLock(
    clock: BallotLockClock,
    onRefreshRoom: () -> Unit,
    voteScheduler: ConferenceVoteScheduler,
): BallotLockHook =
    object : BallotLockHook {
        override fun lockedReason(): String? = consensusLockReason(clock.lock(true).forMember())

        override fun subscribe(listener: () -> Unit): () -> Unit = clock.subscribe(listener)

        override fun onConflict() = onRefreshRoom()

        override val scheduler: ConferenceVoteScheduler = voteScheduler
    }

/**
 * `null` = free; otherwise the reason the final submit of an anonymous consensus is locked, in plain words. Two sentences are two msgids
 * joined with a space, never a sentence built from fragments.
 */
internal fun consensusLockReason(lock: BallotStreamLock): String? {
    if (lock == BallotStreamLock.FREE) return null
    val reason =
        gettext(
            "Ihr Bildschirm wird gerade übertragen. Damit niemand Ihre Bewertung sieht, " +
                "ist die Abgabe gesperrt, bis die Übertragung angehalten ist.",
        )
    return if (lock == BallotStreamLock.LOCKED_SLOW || lock == BallotStreamLock.HUNG) {
        reason + " " + gettext("Das dauert länger als üblich. Bitte haben Sie noch einen Moment Geduld.")
    } else {
        reason
    }
}
