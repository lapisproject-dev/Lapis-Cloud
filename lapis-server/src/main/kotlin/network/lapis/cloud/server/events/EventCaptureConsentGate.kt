package network.lapis.cloud.server.events

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.agreementtexts.AgreementTextResolver
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.conference.liveKitIdentityAsMemberId
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.EventCaptureConsentCountDto
import network.lapis.cloud.shared.rpc.EventRoomCaptureConsentMissingException
import network.lapis.cloud.shared.rpc.ServiceBusyException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

// Privacy rule of this file: logs carry numbers and exception classes only -- never an identity, a room, an event or a name.
private val logger = KotlinLogging.logger {}

/**
 * V1.9.280 -- who is in an event room right now, collected OUTSIDE any transaction (LiveKit is a network call). [liveKitIdentities] are the connected
 * participants (the recording egress already left out), [pendingGuestIdentities] the account-less guests who hold a minted, unexpired token but may not
 * have connected yet.
 */
internal data class EventRoomPresence(
    val liveKitIdentities: Set<String>,
    val pendingGuestIdentities: Set<String>,
    /**
     * `true` when the presence of an EVENT room could not be learned (LiveKit unreachable, nothing installed). The gate then refuses with
     * [ServiceBusyException] -- fail closed -- but only when it is reached, i.e. AFTER the authorization and the availability checks of the start, so a
     * stranger or a blocked event still gets the refusal they always got.
     */
    val unavailable: Boolean = false,
) {
    companion object {
        /** Nobody known -- used for an ordinary room (the gate does not apply to it). */
        val EMPTY = EventRoomPresence(liveKitIdentities = emptySet(), pendingGuestIdentities = emptySet())

        /** The presence of an event room could not be learned. */
        val UNAVAILABLE = EventRoomPresence(liveKitIdentities = emptySet(), pendingGuestIdentities = emptySet(), unavailable = true)
    }
}

/**
 * V1.9.280 -- THE gate at the START of a recording / stream of an event room: it starts only when EVERY identity in the room holds an acknowledgment of
 * the CURRENT `EVENT_REC` text (version AND hash). Everything else about "who may be told what" lives elsewhere; this object only answers the question and
 * enforces it.
 *
 * - `requireEveryoneAcknowledged` is called ONLY from [EventConferenceGuard.requireEventCaptureAllowed] (tripwire `EventConferenceGuardTripwireTest`).
 * - The presence is collected BEFORE the starting transaction ([collectForRoom]); a LiveKit failure is a refusal (`ServiceBusyException`, fail closed).
 * - Who counts: a UUID identity needs a member proof, an `evg-` identity a guest-key proof; the egress is left out; EVERY OTHER identity counts as not
 *   acknowledged. With the built-in placeholder text (no published text) nobody is acknowledged.
 * - The names of the people who are missing never leave this object (not into a log, not into a DTO, not into an exception).
 *
 * **Lock order.** The start locks the ROOM row only; this gate then READS the proofs and the participations. It never locks the event row (join and
 * switch-off lock event -> room; a start that took room -> event would be a deadlock).
 */
internal object EventCaptureConsentGate {
    /** What the gate needs from the outside: the LiveKit admin boundary, the join coordinator (pending guests) and the server secret of the guest keys. */
    internal class Context(
        val liveKit: LiveKitAdminClient,
        val join: EventOnlineJoin?,
        val guestKeySecret: String,
    )

    @Volatile
    private var context: Context? = null

    /** Installed once by `Application.module` (and by tests). While nothing is installed, an event room can never be started (fail closed). */
    fun install(context: Context?) {
        this.context = context
    }

    /** Result of [count]; [missingIdentities] stays inside the server (the enforcement removes exactly those). */
    internal data class Count(
        val acknowledged: Int,
        val total: Int,
        val missingIdentities: Set<String>,
    ) {
        fun toDto(): EventCaptureConsentCountDto = EventCaptureConsentCountDto(acknowledged = acknowledged, total = total)
    }

    /**
     * The presence of the event room [roomId], or [EventRoomPresence.EMPTY] for an ordinary room and for a room that has ended (the start refuses that
     * itself). Opens its own short read transaction for the room name, calls LiveKit OUTSIDE of it. A LiveKit failure (or no installed context for an
     * event room) is [EventRoomPresence.UNAVAILABLE]: it does not throw here but makes [requireEveryoneAcknowledged] refuse (fail closed), so the
     * refusals of the checks that come first keep their order.
     */
    suspend fun collectForRoom(roomId: Uuid): EventRoomPresence {
        val roomName =
            transaction {
                if (EventConferenceGuard.eventIdOfRoom(roomId) == null) {
                    null
                } else {
                    ConferenceRoomTable
                        .selectAll()
                        .where { (ConferenceRoomTable.id eq roomId) and ConferenceRoomTable.endedAt.isNull() }
                        .singleOrNull()
                        ?.get(ConferenceRoomTable.livekitRoomName)
                }
            } ?: return EventRoomPresence.EMPTY
        val ctx = context ?: return EventRoomPresence.UNAVAILABLE
        return try {
            collect(livekitRoomName = roomName, liveKit = ctx.liveKit, join = ctx.join)
        } catch (e: ServiceBusyException) {
            EventRoomPresence.UNAVAILABLE
        }
    }

    /** Never called inside a transaction. Fail closed: a LiveKit error is [ServiceBusyException]. */
    suspend fun collect(
        livekitRoomName: String,
        liveKit: LiveKitAdminClient,
        join: EventOnlineJoin?,
    ): EventRoomPresence {
        val connected =
            try {
                liveKit
                    .listParticipants(livekitRoomName)
                    .filterNot { it.kind == KIND_EGRESS }
                    .map { it.identity }
                    .toSet()
            } catch (e: LiveKitAdminException) {
                logger.warn { "event capture consent: LiveKit not reachable (${e::class.simpleName})" }
                throw ServiceBusyException()
            }
        join?.noteConnected(livekitRoomName = livekitRoomName, connected = connected)
        val pending = join?.pendingGuestIdentities(livekitRoomName = livekitRoomName, now = ServerClock.nowInstant()) ?: emptySet()
        return EventRoomPresence(liveKitIdentities = connected, pendingGuestIdentities = pending)
    }

    /**
     * In the caller's transaction. The set that must have acknowledged is LiveKit identities + pending guest tokens + open participations (a member with
     * an open participation but no LiveKit connection yet can still connect with the token he holds).
     */
    fun count(
        eventId: Uuid,
        roomId: Uuid,
        present: EventRoomPresence,
        guestKeySecret: String? = context?.guestKeySecret,
    ): Count {
        val openMembers =
            ConferenceParticipationTable
                .selectAll()
                .where { (ConferenceParticipationTable.roomId eq roomId) and ConferenceParticipationTable.leftAt.isNull() }
                .map { it[ConferenceParticipationTable.memberId].toString() }
        val everyone = present.liveKitIdentities + present.pendingGuestIdentities + openMembers
        if (everyone.isEmpty()) return Count(acknowledged = 0, total = 0, missingIdentities = emptySet())
        val current = AgreementTextResolver.current(kind = AgreementTextKind.EVENT_REC)
        // The placeholder is never a basis for anything: nobody counts as acknowledged.
        if (current.placeholder) return Count(acknowledged = 0, total = everyone.size, missingIdentities = everyone)

        val memberByIdentity = everyone.mapNotNull { id -> liveKitIdentityAsMemberId(id)?.let { id to it } }.toMap()
        val secret = guestKeySecret
        val guestKeyByIdentity =
            if (secret == null) {
                emptyMap()
            } else {
                everyone
                    .filter { it.startsWith(EVENT_GUEST_IDENTITY_PREFIX) }
                    .associateWith { EventCaptureAcknowledgments.guestKeyOf(identity = it, serverSecret = secret) }
            }
        val (acknowledgedMembers, acknowledgedGuestKeys) =
            EventCaptureAcknowledgments.acknowledgedAmong(
                eventId = eventId,
                memberIds = memberByIdentity.values,
                guestKeys = guestKeyByIdentity.values,
                consentVersion = current.versionLabel,
                consentSha256 = current.sha256,
            )
        val missing =
            everyone
                .filterNot { id ->
                    val member = memberByIdentity[id]
                    val guestKey = guestKeyByIdentity[id]
                    (member != null && member in acknowledgedMembers) || (guestKey != null && guestKey in acknowledgedGuestKeys)
                }.toSet()
        return Count(acknowledged = everyone.size - missing.size, total = everyone.size, missingIdentities = missing)
    }

    /** In the caller's transaction (the room row is locked). Throws [EventRoomCaptureConsentMissingException] -- no name, no number. */
    fun requireEveryoneAcknowledged(
        eventId: Uuid,
        roomId: Uuid,
        present: EventRoomPresence,
    ) {
        if (present.unavailable) throw ServiceBusyException()
        if (count(eventId = eventId, roomId = roomId, present = present).missingIdentities.isNotEmpty()) {
            throw EventRoomCaptureConsentMissingException()
        }
    }

    /**
     * While a recording / stream RUNS, whoever is in the room without an acknowledgment is removed: the connection is cut and a member's participation is
     * closed (`leftAt`). That covers a return with an old, still valid token, the return from a breakout and a connection that was made between the
     * collection of the presence and the commit of the start. Never inside a transaction. Logs the NUMBER of removals only. Returns that number.
     */
    suspend fun removeUnacknowledged(
        liveKit: LiveKitAdminClient,
        livekitRoomName: String,
        roomId: Uuid,
        missing: Set<String>,
        connected: Set<String>,
    ): Int {
        var removed = 0
        // LiveKit is only asked about identities that are in the room; the others (pending guests, open participations of a crashed browser) are not.
        for (identity in missing.filter { it in connected }) {
            try {
                liveKit.removeParticipant(room = livekitRoomName, identity = identity)
                removed++
            } catch (e: LiveKitAdminException) {
                logger.warn { "event capture consent: a participant could not be removed (${e::class.simpleName})" }
            }
        }
        val memberIds = missing.mapNotNull { liveKitIdentityAsMemberId(it) }
        if (memberIds.isNotEmpty()) {
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceParticipationTable.update({
                    (ConferenceParticipationTable.roomId eq roomId) and
                        (ConferenceParticipationTable.memberId inList memberIds) and
                        ConferenceParticipationTable.leftAt.isNull()
                }) {
                    it[leftAt] = now
                }
            }
        }
        if (removed > 0) logger.info { "event capture consent: $removed participant(s) without consent removed" }
        return removed
    }

    private const val KIND_EGRESS = "EGRESS"
}
