package network.lapis.cloud.server.encounter

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Welle V1.9.76 -- the ONLY state of the anonymous entry notice for office holders: whether the first guest of a session was
 * already announced (FIRST_GUEST), how many persons without an office entered into which five-minute slot (EVERY_GUEST), and when a
 * space last sent a notice. **Held in memory only, never persisted** (Art. 9 GDPR: a database row would be a lasting record that
 * somebody attended a church service), same posture as [EncounterModerationState]. Consequences, documented in
 * `docs/architecture/encounter-space.adoc`: a restart forgets it (a first guest may be announced twice), several server instances do
 * not share it, and it is dropped when the session ends or the notify mode changes.
 *
 * **Anonymous by construction**: no function takes a member id or an identity; keys are session/space ids, values are `Boolean`,
 * `Int` and [Instant]. There is deliberately no way to record WHO entered.
 *
 * Bounded: at most [MAX_SESSIONS] tracked sessions per kind and [MAX_PENDING_SLOTS_PER_SESSION] unsent slots per session; a full state
 * REFUSES the entry (no mail) instead of evicting older state. Thread-safe (a single monitor; every operation is tiny).
 *
 * The five-minute grid is aligned to the UTC epoch, which equals the organisation's wall-clock grid for every zone whose UTC offset
 * is a multiple of five minutes (all current zones); the notifier renders the slot bounds in the organisation zone.
 */
class EncounterEntryNoticeState {
    /** One slot of persons without an office whose notice is due: [windowStart] until [windowEnd] (exclusive), [count] entries. */
    data class DueWindow(
        val sessionRoomId: Uuid,
        val spaceId: Uuid,
        val windowStart: Instant,
        val windowEnd: Instant,
        val count: Int,
    )

    private class Counted(
        val spaceId: Uuid,
        /** slot start in epoch seconds -> number of entries */
        val slots: MutableMap<Long, Int> = HashMap(),
    )

    private val lock = Any()

    /** sessionRoomId -> spaceId of the sessions whose first guest was already claimed. */
    private val firstGuestClaimed = HashMap<Uuid, Uuid>()
    private val counted = HashMap<Uuid, Counted>()
    private val lastSentAt = HashMap<Uuid, Instant>()

    /** FIRST_GUEST: `true` exactly once per session (atomic check-and-set); `false` if already claimed or the state is full. */
    fun claimFirstGuest(
        sessionRoomId: Uuid,
        spaceId: Uuid,
    ): Boolean =
        synchronized(lock) {
            if (sessionRoomId in firstGuestClaimed) return false
            if (firstGuestClaimed.size >= MAX_SESSIONS) return false
            firstGuestClaimed[sessionRoomId] = spaceId
            true
        }

    /** EVERY_GUEST: counts one entry into the grid slot containing [now]; `false` if the state is full (no mail, never an eviction). */
    fun countEntry(
        sessionRoomId: Uuid,
        spaceId: Uuid,
        now: Instant,
    ): Boolean =
        synchronized(lock) {
            val slotStart = slotStartSeconds(now)
            val entry =
                counted[sessionRoomId]
                    ?: run {
                        if (counted.size >= MAX_SESSIONS) return false
                        Counted(spaceId = spaceId).also { counted[sessionRoomId] = it }
                    }
            val current = entry.slots[slotStart]
            if (current == null && entry.slots.size >= MAX_PENDING_SLOTS_PER_SESSION) return false
            entry.slots[slotStart] = (current ?: 0) + 1
            true
        }

    /** `true` iff the state cannot take a further session (diagnostic: lets the caller log "state full" without guessing). */
    fun isFull(): Boolean = synchronized(lock) { firstGuestClaimed.size >= MAX_SESSIONS || counted.size >= MAX_SESSIONS }

    /**
     * Atomically removes and returns the windows whose end has passed AND whose space last sent at least [WINDOW] ago. All due slots of
     * one session are merged into ONE window (so a late flush still yields at most one mail per space); a space that sent less than
     * [WINDOW] ago keeps its slots until the throttle has elapsed.
     */
    fun drainDue(now: Instant): List<DueWindow> =
        synchronized(lock) {
            val due = mutableListOf<DueWindow>()
            val sentThisDrain = HashSet<Uuid>()
            val iterator = counted.entries.iterator()
            while (iterator.hasNext()) {
                val (sessionRoomId, entry) = iterator.next()
                val spaceId = entry.spaceId
                val throttled = lastSentAt[spaceId]?.let { now < it + WINDOW } == true || spaceId in sentThisDrain
                val dueSlots = entry.slots.filterKeys { Instant.fromEpochSeconds(it) + WINDOW <= now }
                if (throttled || dueSlots.isEmpty()) continue
                dueSlots.keys.forEach { entry.slots.remove(it) }
                sentThisDrain += spaceId
                // Claimed at drain time, not after the send: a second drain racing the send must already see the throttle.
                markSentLocked(spaceId = spaceId, at = now)
                due +=
                    DueWindow(
                        sessionRoomId = sessionRoomId,
                        spaceId = spaceId,
                        windowStart = Instant.fromEpochSeconds(dueSlots.keys.min()),
                        windowEnd = Instant.fromEpochSeconds(dueSlots.keys.max()) + WINDOW,
                        count = dueSlots.values.sum(),
                    )
                if (entry.slots.isEmpty()) iterator.remove()
            }
            due
        }

    /** Records that [spaceId] sent a notice at [at] (the throttle of EVERY_GUEST). Old marks are pruned. */
    fun markSent(
        spaceId: Uuid,
        at: Instant,
    ) {
        synchronized(lock) { markSentLocked(spaceId = spaceId, at = at) }
    }

    private fun markSentLocked(
        spaceId: Uuid,
        at: Instant,
    ) {
        lastSentAt.entries.removeAll { it.value + WINDOW <= at }
        if (lastSentAt.size < MAX_SESSIONS * 4 || spaceId in lastSentAt) lastSentAt[spaceId] = at
    }

    /** Drops everything known about [sessionRoomId] (session closed, poller closed it). The throttle of its space stays. */
    fun clearSession(sessionRoomId: Uuid) {
        synchronized(lock) {
            firstGuestClaimed.remove(sessionRoomId)
            counted.remove(sessionRoomId)
        }
    }

    /** Drops the marker and the pending slots of every session of [spaceId] (the notify mode changed). The throttle stays. */
    fun clearSpace(spaceId: Uuid) {
        synchronized(lock) {
            firstGuestClaimed.values.removeAll { it == spaceId }
            counted.values.removeAll { it.spaceId == spaceId }
        }
    }

    /** Number of sessions with any state (test/diagnostic aid). */
    fun trackedSessions(): Int = synchronized(lock) { (firstGuestClaimed.keys + counted.keys).size }

    companion object {
        const val MAX_SESSIONS = 64
        const val MAX_PENDING_SLOTS_PER_SESSION = 4
        val WINDOW: Duration = 5.minutes

        private fun slotStartSeconds(now: Instant): Long {
            val seconds = now.epochSeconds
            return seconds - Math.floorMod(seconds, WINDOW.inWholeSeconds)
        }
    }
}
