package network.lapis.cloud.client.encounter

import io.kvision.i18n.gettext
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.ENCOUNTER_MAP_ANCHOR_COOLDOWN_MS
import network.lapis.cloud.shared.domain.ENCOUNTER_MAP_CLIENT_STEP_MS
import network.lapis.cloud.shared.domain.ENCOUNTER_MAP_NEAR_NAMES_MAX
import network.lapis.cloud.shared.domain.ENCOUNTER_MAP_NEAR_RADIUS
import network.lapis.cloud.shared.domain.ENCOUNTER_MAP_PATH_MAX_STEPS
import network.lapis.cloud.shared.domain.EncounterMapAnchor
import network.lapis.cloud.shared.domain.EncounterMapFacing
import network.lapis.cloud.shared.domain.EncounterMapMove
import network.lapis.cloud.shared.domain.EncounterMapPacket
import network.lapis.cloud.shared.domain.EncounterMapPositionDto
import network.lapis.cloud.shared.domain.EncounterMapSelfDto
import network.lapis.cloud.shared.domain.EncounterMapSnapshotDto
import network.lapis.cloud.shared.domain.EncounterMapTemplate
import network.lapis.cloud.shared.domain.EncounterMapTemplates
import network.lapis.cloud.shared.domain.EncounterRoomLayout
import network.lapis.cloud.shared.domain.encounterMapIndex
import network.lapis.cloud.shared.domain.encounterMapPath
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import network.lapis.cloud.shared.rpc.ServiceBusyException
import kotlin.math.abs

/** At the earliest after this many milliseconds a missed packet makes the map fetch the snapshot again. */
internal const val ENCOUNTER_MAP_RESYNC_GAP_MS = 2_000.0

/** A busy-refused snapshot is retried after this long (a little more than the server's 2 s window), at most [ENCOUNTER_MAP_BUSY_RETRIES] times in a row. */
internal const val ENCOUNTER_MAP_BUSY_RETRY_MS = 2_100L
internal const val ENCOUNTER_MAP_BUSY_RETRIES = 3

/** The "path blocked" sentence is spoken at most this often. */
private const val BLOCKED_ANNOUNCE_GAP_MS = 2_000.0

/** The own position is spoken at most this often. */
private const val POSITION_ANNOUNCE_GAP_MS = 1_000.0

/** Everything the map view needs for one picture. Ids and coordinates only -- the NAMES come from the presence list the room already holds. */
internal class EncounterMapModel(
    val template: EncounterMapTemplate,
    val people: List<EncounterMapPositionDto>,
    val selfId: String,
    val selfOnMap: Boolean,
    val canWalk: Boolean,
    /** The one fixed status sentence ("Reihe 3, Spalte 5" or "Sie stehen nicht auf der Karte."). */
    val status: String,
    /** The names of the people in the vicinity (at most [ENCOUNTER_MAP_NEAR_NAMES_MAX]), sorted. */
    val near: List<String>,
    /** How many more people are in the vicinity than [near] names. */
    val nearMore: Int,
    /** An anchor jump is on its way or in its cooldown: the anchor buttons show `aria-disabled` (never the native `disabled`). */
    val anchorsBusy: Boolean = false,
)

/**
 * V1.9.166 (encounter stage 3, wave 1) -- the DOM-free logic of the grid map of one tab. **The server is the only authority**: this class
 * never moves a figure on its own, it sends a DIRECTION (never a coordinate) and shows what the server answered; a packet from the server
 * is the other source of positions. Nothing is stored anywhere (no `localStorage`, no cookie, no URL): a position lives in this object and
 * dies with the tab.
 *
 * - Built only for a MEMBER ([canWalk] = the person may step onto the map, an office holder looks only). A guest never gets a controller and
 *   so never calls a map method (the server refuses them anyway with one bare answer).
 * - Missed packets: every delta carries an `epoch` (a server start) and a `seq`; another epoch, a gap or a reset fetches the snapshot again,
 *   at the earliest [ENCOUNTER_MAP_RESYNC_GAP_MS] after the last fetch.
 * - Pace: at most one step per [ENCOUNTER_MAP_CLIENT_STEP_MS] and never two requests at once (the server limits again).
 * - Announcements are fixed sentences without a name; the vicinity list is read on request only.
 */
internal class EncounterMapController(
    private val spaceId: String,
    roomLayout: EncounterRoomLayout,
    private val selfId: String,
    private val canWalk: Boolean,
    private val rpc: IEncounterSpaceService,
    private val scope: CoroutineScope,
    private val onRender: (EncounterMapModel) -> Unit,
    private val announce: (String) -> Unit,
    /** Display name of a member from the presence list; `null` = unknown (the initials are used). */
    private val nameOf: (String) -> String?,
    /** The room reloads its presence list and nudges the others (a bench or table seat was given up by entering the map). */
    private val onEnteredMap: () -> Unit = {},
    private val now: () -> Double = { window.performance.now() },
    /** A snapshot the server refused as "busy" (its rate limit) is tried again after this many milliseconds. */
    private val busyRetryMs: Long = ENCOUNTER_MAP_BUSY_RETRY_MS,
) {
    /** The room template the board is drawn from (the layout of the room cannot change while it is open). */
    val template: EncounterMapTemplate =
        EncounterMapTemplates.of(roomLayout) ?: EncounterMapTemplates.of(EncounterRoomLayout.HALL)!!
    private val people = LinkedHashMap<String, EncounterMapPositionDto>()
    private var epoch: String? = null
    private var lastSeq: Long = -1
    private var lastSnapshotAt = Double.NEGATIVE_INFINITY
    private var snapshotRunning = false
    private var snapshotScheduled: Job? = null
    private var busyRetries = 0
    private var lastStepAt = Double.NEGATIVE_INFINITY
    private var requestRunning = false
    private var walkJob: Job? = null
    private var lastBlockedAt = Double.NEGATIVE_INFINITY
    private var lastPositionAt = Double.NEGATIVE_INFINITY
    private var lastNearCount = -1
    private var disposed = false
    private var anchorCooldownUntil = Double.NEGATIVE_INFINITY
    private var anchorNoticeGiven = false
    private var teleportPending = false
    private var cooldownTimer: Job? = null

    /** `true` while an anchor jump is on its way or its cooldown runs (a walking step does not count). */
    val anchorsBusy: Boolean get() = teleportPending || now() < anchorCooldownUntil

    val selfOnMap: Boolean get() = people.containsKey(selfId)

    /** Loads the first snapshot (registers this tab for the packets). */
    fun start() {
        requestSnapshot(force = true)
    }

    /** A packet from the server (never from a participant; strictly parsed already). */
    fun onPacket(packet: EncounterMapPacket) {
        if (disposed) return
        val known = epoch
        if (known == null) return // the first snapshot is on its way
        if (packet.epoch != known) {
            requestSnapshot(force = false)
            return
        }
        when (packet) {
            is EncounterMapPacket.Reset -> requestSnapshot(force = false)
            is EncounterMapPacket.Delta -> {
                if (packet.seq <= lastSeq) return // old or duplicate
                val gap = packet.seq != lastSeq + 1
                packet.changed.forEach { people[it.memberId] = it }
                packet.removed.forEach { people.remove(it) }
                lastSeq = packet.seq
                if (gap) requestSnapshot(force = false)
                render()
            }
        }
    }

    fun enterMap() {
        if (disposed || !canWalk || requestRunning) return
        requestRunning = true
        scope.launch {
            try {
                val self = rpc.mapEnter(spaceId)
                if (self.onMap) onEnteredMap()
                // The answer already says where the figure stands; the snapshot below may be refused (rate limit) and must not be the only source.
                applySelf(self, announceMove = false)
                snapshotNow()
                announceOwnPosition(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ServiceBusyException) {
                announce(gettext("Die Karte ist voll oder Sie waren zu schnell."))
            } catch (e: ForbiddenException) {
                walkRefused()
            } catch (e: Throwable) {
                announce(gettext("Die Karte ist voll oder Sie waren zu schnell."))
            } finally {
                requestRunning = false
            }
        }
    }

    fun leaveMap() {
        if (disposed || !selfOnMap) return
        walkJob?.cancel()
        dropSelf()
        scope.launch {
            try {
                rpc.mapLeave(spaceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The server removes the position by itself (reconciler, hooks); nothing to repair here.
            }
        }
    }

    /** One step. A press inside the pace gap does nothing; the walk of a click is cancelled by a manual step. */
    fun step(dir: EncounterMapFacing) {
        if (disposed || !canWalk || !selfOnMap) return
        walkJob?.cancel()
        walkJob = null
        val t = now()
        if (t - lastStepAt < ENCOUNTER_MAP_CLIENT_STEP_MS || requestRunning) return
        lastStepAt = t
        requestRunning = true
        scope.launch {
            try {
                applySelf(rpc.mapStep(spaceId, dir))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ForbiddenException) {
                walkRefused()
            } catch (e: Throwable) {
                // A lost step is repaired by the next packet; nothing is announced for it.
            } finally {
                requestRunning = false
            }
        }
    }

    /** Click on a tile: walk there along the shortest path, one request per step, [ENCOUNTER_MAP_CLIENT_STEP_MS] apart; any refusal ends the walk. */
    fun walkTo(index: Int) {
        if (disposed || !canWalk || !selfOnMap) return
        val me = people[selfId] ?: return
        val occupied =
            people
                .filterKeys { it != selfId }
                .values
                .map { encounterMapIndex(it.x, it.y) }
                .toSet()
        val path = encounterMapPath(template, occupied, encounterMapIndex(me.x, me.y), index, ENCOUNTER_MAP_PATH_MAX_STEPS)
        if (path == null) {
            announceBlocked()
            return
        }
        if (path.isEmpty()) return
        walkJob?.cancel()
        walkJob =
            scope.launch {
                for (dir in path) {
                    if (disposed || !selfOnMap) return@launch
                    // wait out a request that is still running (a click and a key together)
                    while (requestRunning) delay(PATIENCE_MS)
                    requestRunning = true
                    val move =
                        try {
                            val self = rpc.mapStep(spaceId, dir)
                            applySelf(self, announceMove = false)
                            self.move
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: ForbiddenException) {
                            walkRefused()
                            null
                        } catch (e: Throwable) {
                            null
                        } finally {
                            requestRunning = false
                        }
                    if (move != EncounterMapMove.MOVED) {
                        if (move == EncounterMapMove.BLOCKED) announceBlocked()
                        return@launch
                    }
                    delay(ENCOUNTER_MAP_CLIENT_STEP_MS.toLong())
                }
                announceOwnPosition(force = false)
            }
    }

    /**
     * Jump to an anchor. EVERY click is answered: inside the cooldown (the server allows one jump per second) no request is sent, but one
     * spoken notice per cooldown phase says why; a request that is refused (throttled, rate limit) gets the same notice. A step that is still
     * running is waited out (like [walkTo] does) instead of swallowing the click.
     */
    fun teleport(anchor: EncounterMapAnchor) {
        if (disposed || !canWalk || !selfOnMap) return
        if (now() >= anchorCooldownUntil && !teleportPending) anchorNoticeGiven = false
        if (teleportPending || now() < anchorCooldownUntil) {
            announceAnchorBusy()
            return
        }
        // Set synchronously so that a second click (even while the step below is waited out) sees the cooldown and sends nothing.
        teleportPending = true
        armCooldown()
        walkJob?.cancel()
        render()
        scope.launch {
            try {
                while (requestRunning) delay(PATIENCE_MS)
                if (disposed || !selfOnMap) return@launch
                requestRunning = true
                try {
                    val self = rpc.mapTeleport(spaceId, anchor)
                    // the server stamps the jump on arrival: count the cooldown from the answer
                    armCooldown()
                    if (self.move == EncounterMapMove.THROTTLED) announceAnchorBusy()
                    applySelf(self)
                } finally {
                    requestRunning = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ForbiddenException) {
                anchorCooldownUntil = Double.NEGATIVE_INFINITY
                walkRefused()
            } catch (e: ServiceBusyException) {
                armCooldown()
                announceAnchorBusy()
            } catch (e: Throwable) {
                // a failed request is not silent either: say so, and let the person try again at once
                anchorCooldownUntil = Double.NEGATIVE_INFINITY
                announceAnchorBusy()
            } finally {
                teleportPending = false
                scheduleCooldownEnd()
                render()
            }
        }
    }

    private fun armCooldown() {
        anchorCooldownUntil = now() + ENCOUNTER_MAP_ANCHOR_COOLDOWN_MS
        scheduleCooldownEnd()
    }

    /** Repaints when the cooldown is over (nothing else would, and the buttons would stay `aria-disabled`). */
    private fun scheduleCooldownEnd() {
        cooldownTimer?.cancel()
        if (disposed) return
        val remaining = anchorCooldownUntil - now()
        if (remaining <= 0) {
            anchorNoticeGiven = false
            return
        }
        cooldownTimer =
            scope.launch {
                delay(remaining.toLong() + 1)
                if (!teleportPending && now() >= anchorCooldownUntil) {
                    anchorNoticeGiven = false
                    render()
                }
            }
    }

    private fun announceAnchorBusy() {
        if (anchorNoticeGiven) return
        anchorNoticeGiven = true
        announce(gettext("Einen Moment bitte, gleich können Sie wieder springen."))
    }

    /** The status sentence: the own tile and, if anybody is near, how many. */
    fun describe(): String {
        val me = people[selfId] ?: return gettext("Sie stehen nicht auf der Karte.")
        val place = gettext("Reihe %1, Spalte %2", me.y + 1, me.x + 1)
        val count = nearIds().size
        return when (count) {
            0 -> place
            1 -> "$place · ${gettext("Eine Person in Ihrer Nähe")}"
            else -> "$place · ${gettext("%1 Personen in Ihrer Nähe", count)}"
        }
    }

    /** The names near the own tile, sorted, at most [ENCOUNTER_MAP_NEAR_NAMES_MAX]. */
    fun near(): List<String> = nearIds().map { displayOf(it) }.sorted().take(ENCOUNTER_MAP_NEAR_NAMES_MAX)

    /** The sentence of the "L" key: the status plus the names. */
    fun describeWithNames(): String {
        val names = near()
        val more = nearIds().size - names.size
        if (names.isEmpty()) return describe()
        val list = names.joinToString(", ") + if (more > 0) " " + gettext("und %1 weitere", more) else ""
        return "${describe()} · ${gettext("In Ihrer Nähe")}: $list"
    }

    /** Called when the presence list changed: names may have appeared. */
    fun refreshNames() {
        if (!disposed) render()
    }

    fun dispose() {
        disposed = true
        walkJob?.cancel()
        cooldownTimer?.cancel()
        snapshotScheduled?.cancel()
        people.clear()
    }

    // ── internals ─────────────────────────────────────────────────────────

    private fun requestSnapshot(force: Boolean) {
        if (disposed || snapshotRunning) return
        val wait = ENCOUNTER_MAP_RESYNC_GAP_MS - (now() - lastSnapshotAt)
        if (force || wait <= 0) {
            snapshotScheduled?.cancel()
            snapshotScheduled = null
            scope.launch { snapshotNow() }
        } else if (snapshotScheduled == null) {
            snapshotScheduled =
                scope.launch {
                    delay(wait.toLong() + 1)
                    snapshotScheduled = null
                    snapshotNow()
                }
        }
    }

    private suspend fun snapshotNow() {
        if (disposed || snapshotRunning) return
        snapshotRunning = true
        lastSnapshotAt = now()
        try {
            applySnapshot(rpc.mapSnapshot(spaceId))
            busyRetries = 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: ForbiddenException) {
            // The map was switched off or the right to stand on it is gone: no position may stay on screen.
            dropAll()
        } catch (e: ServiceBusyException) {
            scheduleBusyRetry()
        } catch (e: Throwable) {
            // offline: the next packet or the resync tries again
        } finally {
            snapshotRunning = false
        }
    }

    private fun scheduleBusyRetry() {
        if (disposed || snapshotScheduled != null || busyRetries >= ENCOUNTER_MAP_BUSY_RETRIES) return
        busyRetries++
        snapshotScheduled =
            scope.launch {
                delay(busyRetryMs)
                snapshotScheduled = null
                snapshotNow()
            }
    }

    /**
     * Forbidden on enter, step, walk or teleport only means "may not walk now" (silenced, seated, ...), not "may not look":
     * drop the own figure and ask for a fresh snapshot; only a Forbidden snapshot clears the board ([dropAll]).
     */
    private fun walkRefused() {
        walkJob?.cancel()
        dropSelf()
        requestSnapshot(force = true)
    }

    /** Forbidden on the snapshot: forget every figure, draw an empty board and ignore packets until a snapshot succeeds again. */
    private fun dropAll() {
        walkJob?.cancel()
        val hadSelf = people.containsKey(selfId)
        people.clear()
        epoch = null
        lastSeq = -1
        lastNearCount = -1
        render()
        if (hadSelf) announce(gettext("Sie stehen nicht auf der Karte."))
    }

    private fun applySnapshot(snapshot: EncounterMapSnapshotDto) {
        people.clear()
        snapshot.positions.forEach { people[it.memberId] = it }
        epoch = snapshot.epoch
        lastSeq = snapshot.seq
        render()
    }

    private fun applySelf(
        self: EncounterMapSelfDto,
        announceMove: Boolean = true,
    ) {
        if (!self.onMap) {
            dropSelf()
            return
        }
        val old = people[selfId]
        people[selfId] =
            EncounterMapPositionDto(
                memberId = selfId,
                x = self.x,
                y = self.y,
                facing = self.facing,
                initials = old?.initials ?: encounterInitials(nameOf(selfId) ?: ""),
            )
        render()
        when (self.move) {
            EncounterMapMove.BLOCKED -> announceBlocked()
            EncounterMapMove.MOVED -> if (announceMove) announceOwnPosition(force = false)
            EncounterMapMove.THROTTLED -> Unit
        }
    }

    private fun dropSelf() {
        if (people.remove(selfId) != null) {
            cooldownTimer?.cancel()
            lastNearCount = -1
            render()
            announce(gettext("Sie stehen nicht auf der Karte."))
        }
    }

    private fun announceBlocked() {
        val t = now()
        if (t - lastBlockedAt < BLOCKED_ANNOUNCE_GAP_MS) return
        lastBlockedAt = t
        announce(gettext("Weg blockiert"))
    }

    /** The own place, at most once per second; the count of people nearby only when it changed. */
    private fun announceOwnPosition(force: Boolean) {
        val me = people[selfId] ?: return
        val t = now()
        val count = nearIds().size
        val countChanged = count != lastNearCount
        if (!force && t - lastPositionAt < POSITION_ANNOUNCE_GAP_MS) return
        lastPositionAt = t
        lastNearCount = count
        val place = gettext("Reihe %1, Spalte %2", me.y + 1, me.x + 1)
        announce(if (countChanged) describe() else place)
    }

    private fun nearIds(): List<String> {
        val me = people[selfId] ?: return emptyList()
        return people.values
            .filter {
                it.memberId != selfId &&
                    abs(it.x - me.x) <= ENCOUNTER_MAP_NEAR_RADIUS &&
                    abs(it.y - me.y) <= ENCOUNTER_MAP_NEAR_RADIUS
            }.map { it.memberId }
    }

    private fun displayOf(memberId: String): String = nameOf(memberId) ?: people[memberId]?.initials ?: "?"

    private fun render() {
        if (disposed) return
        val names = near()
        onRender(
            EncounterMapModel(
                template = template,
                people = people.values.toList(),
                selfId = selfId,
                selfOnMap = selfOnMap,
                canWalk = canWalk,
                status = describe(),
                near = names,
                nearMore = (nearIds().size - names.size).coerceAtLeast(0),
                anchorsBusy = anchorsBusy,
            ),
        )
    }

    private companion object {
        const val PATIENCE_MS = 25L
    }
}

private val EncounterMapPacket.epoch: String
    get() =
        when (this) {
            is EncounterMapPacket.Delta -> epoch
            is EncounterMapPacket.Reset -> epoch
        }
