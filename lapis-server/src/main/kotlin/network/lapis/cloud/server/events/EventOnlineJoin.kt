package network.lapis.cloud.server.events

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.conference.ConferenceAudioGrantState
import network.lapis.cloud.server.conference.ConferenceAudioPolicy
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.LiveKitAccessToken
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.conference.TurnCredentialMinter
import network.lapis.cloud.server.conference.isEventGuestIdentity
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ConferenceGuestConsentAcknowledgmentTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.server.db.generated.EventConferenceTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OidcGuestProfileTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.encounter.ConferenceModeratorAuthority
import network.lapis.cloud.server.rpc.NoiseSuppressionDefaultStore
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.ConferenceGuestConsentAcknowledgmentInput
import network.lapis.cloud.shared.domain.ConferenceJoinTokenDto
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.ConferenceTurnServer
import network.lapis.cloud.shared.domain.EventGuestJoinResponseDto
import network.lapis.cloud.shared.domain.EventGuestNotice
import network.lapis.cloud.shared.domain.EventGuestParticipantDto
import network.lapis.cloud.shared.domain.EventOnlineJoinResultDto
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Privacy rule of this file: logs carry exception classes only -- never an event, a name, a token, a ticket code or a room name.
private val logger = KotlinLogging.logger {}

/** LiveKit identity prefix of a person who joined an event without an account -- the moderators' list/remove calls only ever touch these. */
internal const val EVENT_GUEST_IDENTITY_PREFIX = "evg-"

/** Appended server-side to every account-less display name so a guest cannot impersonate a member (`"Vorstand"` becomes `"Vorstand (Gast)"`). */
internal const val EVENT_GUEST_NAME_SUFFIX = " (Gast)"

internal const val EVENT_GUEST_NAME_MAX_LENGTH = 60

/** The floor of a token lifetime: a join right before the window closes must still yield a usable token. */
private val MIN_TOKEN_TTL: Duration = 1.minutes

/**
 * Welle V1.9.113 -- everything "take part online in an event" that needs more than the access decision: it provisions the conference on
 * the first join ([EventConferenceProvisioner]), mints the LiveKit token and TURN credential, and records the participation. Shared by the
 * authenticated RPC (`EventService.joinEventOnline`) and the account-less route (`EventOnlineGuestRoutes`).
 *
 * LiveKit is never called inside a `transaction {}`. The token is minted with an explicit `now` from [network.lapis.cloud.server.time
 * .ServerClock] (the `Clock.System` default of the minters is never used), and the authorization is RE-RUN under a row lock right before
 * the participation is recorded (a registration can be cancelled while the token was being minted).
 */
internal class EventOnlineJoin(
    private val config: ConferenceConfig,
    private val liveKit: LiveKitAdminClient,
    private val provisioner: EventConferenceProvisioner,
    private val audioGrantState: ConferenceAudioGrantState,
) {
    /**
     * Guest tokens that were minted but whose holder has not (yet) shown up in LiveKit: room name -> (identity -> token expiry). Without
     * it a burst of token requests would all see an empty room and could connect together afterwards, exceeding the guest cap and the
     * seats reserved for members. An entry is dropped once its token expired or the identity is seen connected (then LiveKit counts it).
     * Bounded: a room never holds more entries than the guest cap allows, and rooms without entries are removed.
     */
    private val pendingGuests = HashMap<String, MutableMap<String, Instant>>()

    /** A signed-in caller. */
    suspend fun joinMember(
        eventId: Uuid,
        current: CurrentMember,
        guestConsent: ConferenceGuestConsentAcknowledgmentInput?,
        now: Instant,
    ): EventOnlineJoinResultDto {
        if (!config.enabled) return EventOnlineJoinResultDto(outcome = EventOnlineOutcome.UNAVAILABLE)
        val orgZone = OrganizationTimeZone.current()
        val caller = EventOnlineCaller.Session(current)
        val first =
            transaction {
                val row = EventStore.getEventOrNull(eventId)
                val decision = decideEventOnlineAccess(eventRow = row, caller = caller, now = now, orgZone = orgZone)
                Triple(row, decision, row?.get(EventTable.onlineKind))
            }
        val (eventRow, decision, kind) = first
        if (decision.outcome != EventOnlineOutcome.JOINED) return notJoined(decision = decision, eventRow = eventRow, orgZone = orgZone)
        if (kind == EventOnlineKind.LAPIS_ENCOUNTER) return encounterResult(eventRow = eventRow)
        // A non-member account (GUEST/FRIEND) must acknowledge the event notice -- checked before any room is created.
        if (decision.isGuest) requireGuestNotice(guestConsent)

        val ready =
            try {
                provisioner.ensureRoom(eventId = eventId, now = now)
            } catch (e: LiveKitAdminException) {
                logger.warn { "event online join: LiveKit not reachable (${e::class.simpleName})" }
                return EventOnlineJoinResultDto(outcome = EventOnlineOutcome.UNAVAILABLE)
            }
        val room =
            when (ready) {
                is EventConferenceProvisioner.Ready.Room -> ready
                EventConferenceProvisioner.Ready.Preparing -> return EventOnlineJoinResultDto(outcome = EventOnlineOutcome.PREPARING)
                EventConferenceProvisioner.Ready.Failed -> return EventOnlineJoinResultDto(outcome = EventOnlineOutcome.UNAVAILABLE)
                EventConferenceProvisioner.Ready.Ended -> return EventOnlineJoinResultDto(outcome = EventOnlineOutcome.ENDED)
            }
        val window = checkNotNull(decision.window)
        val moderator = decision.role == ConferenceRole.MODERATOR
        if (!moderator && isFull(livekitRoomName = room.livekitRoomName)) {
            return EventOnlineJoinResultDto(outcome = EventOnlineOutcome.FULL)
        }

        val prep =
            transaction {
                val roomRow = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq room.roomId }.single()
                val mic =
                    ConferenceAudioPolicy.microphoneAllowedFor(
                        row = roomRow,
                        memberId = current.memberId,
                        isModerator = ConferenceModeratorAuthority.isModerator(row = roomRow, current = current),
                        grants = audioGrantState,
                    )
                MemberPrep(
                    displayName = MemberTable.selectAll().where { MemberTable.id eq current.memberId }.single()[MemberTable.displayName],
                    microphoneAllowed = mic,
                )
            }
        val ttl = tokenTtl(isGuest = decision.isGuest, window = window, now = now)
        val minted =
            LiveKitAccessToken.mintParticipantToken(
                apiKey = config.apiKey,
                apiSecret = config.apiSecret,
                roomName = room.livekitRoomName,
                identity = current.memberId.toString(),
                displayName = prep.displayName,
                ttl = ttl,
                canPublish = true,
                canPublishData = true,
                microphoneAllowed = prep.microphoneAllowed,
                now = now,
            )
        val turn = turnServers(label = current.memberId.toString(), ttl = ttl, now = now)

        // TOCTOU re-check + participation record, atomically against everything that locks the room row.
        val recheck =
            transaction {
                ConferenceRoomTable
                    .selectAll()
                    .where { ConferenceRoomTable.id eq room.roomId }
                    .forUpdate()
                    .single()
                val freshEvent = EventStore.getEventOrNull(eventId)
                val fresh = decideEventOnlineAccess(eventRow = freshEvent, caller = caller, now = now, orgZone = orgZone)
                if (fresh.outcome != EventOnlineOutcome.JOINED) {
                    return@transaction fresh.outcome
                }
                val roomRow = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq room.roomId }.single()
                if (roomRow[ConferenceRoomTable.endedAt] != null) return@transaction EventOnlineOutcome.ENDED
                val nowUtc = DbClock.nowLocalDateTime()
                if (fresh.isGuest) {
                    val open =
                        (ConferenceParticipationTable innerJoin MemberTable)
                            .selectAll()
                            .where {
                                (ConferenceParticipationTable.roomId eq room.roomId) and
                                    ConferenceParticipationTable.leftAt.isNull() and
                                    (MemberTable.status inList MemberStatusSets.NON_MEMBER)
                            }.count()
                    if (open >=
                        config.maxNonMemberParticipants
                    ) {
                        throw ConflictException("The non-member participant limit of this event room is reached")
                    }
                }
                ConferenceParticipationTable.insert {
                    it[id] = Uuid.random()
                    it[ConferenceParticipationTable.roomId] = room.roomId
                    it[memberId] = current.memberId
                    it[role] = fresh.role ?: ConferenceRole.PARTICIPANT
                    it[joinedAt] = nowUtc
                    it[leftAt] = null
                }
                if (fresh.isGuest) {
                    val homeserver =
                        if (freshStatus(current.memberId) == MemberStatus.GUEST) {
                            OidcGuestProfileTable
                                .selectAll()
                                .where { OidcGuestProfileTable.memberId eq current.memberId }
                                .singleOrNull()
                                ?.get(OidcGuestProfileTable.homeserverUrl)
                        } else {
                            null
                        }
                    ConferenceGuestConsentAcknowledgmentTable.insert {
                        it[id] = Uuid.random()
                        it[ConferenceGuestConsentAcknowledgmentTable.memberId] = current.memberId
                        it[ConferenceGuestConsentAcknowledgmentTable.roomId] = room.roomId
                        it[acknowledgedAt] = nowUtc
                        it[consentVersion] = EventGuestNotice.VERSION
                        it[consentSha256] = EventGuestNotice.SHA256
                        it[ConferenceGuestConsentAcknowledgmentTable.homeserverUrl] = homeserver
                        it[organizationName] =
                            OrganizationSettingsTable
                                .selectAll()
                                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                                .single()[OrganizationSettingsTable.name]
                    }
                }
                EventOnlineOutcome.JOINED
            }
        if (recheck != EventOnlineOutcome.JOINED) return EventOnlineJoinResultDto(outcome = recheck)
        return EventOnlineJoinResultDto(
            outcome = EventOnlineOutcome.JOINED,
            token =
                ConferenceJoinTokenDto(
                    roomId = room.roomId.toString(),
                    livekitRoomName = room.livekitRoomName,
                    serverUrl = config.livekitUrl,
                    token = minted.jwt,
                    identity = current.memberId.toString(),
                    displayName = prep.displayName,
                    role = decision.role ?: ConferenceRole.PARTICIPANT,
                    expiresAt = minted.expiresAt.toLocalDateTime(TimeZone.UTC),
                    turnServers = turn,
                    eventId = eventId.toString(),
                    noiseSuppressionDefault = NoiseSuppressionDefaultStore.currentValue(),
                ),
        )
    }

    /** A caller without an account (the route layer has already applied the rate limits and parsed the request). */
    suspend fun joinAccountless(
        eventId: Uuid,
        displayName: String,
        ticketCodeSha256: String?,
        now: Instant,
    ): EventGuestJoinResponseDto {
        if (!config.enabled) return EventGuestJoinResponseDto(outcome = EventOnlineOutcome.UNAVAILABLE)
        val orgZone = OrganizationTimeZone.current()
        val caller = EventOnlineCaller.Accountless(ticketCodeSha256)
        val (eventRow, decision) =
            transaction {
                val row = EventStore.getEventOrNull(eventId)
                row to decideEventOnlineAccess(eventRow = row, caller = caller, now = now, orgZone = orgZone)
            }
        if (decision.outcome != EventOnlineOutcome.JOINED) {
            val opens =
                if (decision.outcome ==
                    EventOnlineOutcome.NOT_YET
                ) {
                    decision.window
                        ?.opensAt
                        ?.toLocalDateTime(orgZone)
                        ?.toString()
                } else {
                    null
                }
            return EventGuestJoinResponseDto(outcome = decision.outcome, opensAt = opens)
        }
        if (eventRow == null || eventRow[EventTable.onlineKind] != EventOnlineKind.LAPIS_CONFERENCE) {
            return EventGuestJoinResponseDto(outcome = EventOnlineOutcome.FORBIDDEN)
        }
        val ready =
            try {
                provisioner.ensureRoom(eventId = eventId, now = now)
            } catch (e: LiveKitAdminException) {
                logger.warn { "event guest join: LiveKit not reachable (${e::class.simpleName})" }
                return EventGuestJoinResponseDto(outcome = EventOnlineOutcome.UNAVAILABLE)
            }
        val room =
            when (ready) {
                is EventConferenceProvisioner.Ready.Room -> ready
                EventConferenceProvisioner.Ready.Preparing -> return EventGuestJoinResponseDto(outcome = EventOnlineOutcome.PREPARING)
                EventConferenceProvisioner.Ready.Failed -> return EventGuestJoinResponseDto(outcome = EventOnlineOutcome.UNAVAILABLE)
                EventConferenceProvisioner.Ready.Ended -> return EventGuestJoinResponseDto(outcome = EventOnlineOutcome.ENDED)
            }
        // One live connection per ticket: a deterministic identity makes LiveKit replace the older connection of the same ticket, so one
        // confirmed ticket cannot admit any number of people. Without a ticket check (no deadline) the identity stays random.
        val identity =
            if (ticketCodeSha256 != null && eventRow[EventTable.registrationClosesAt] != null) {
                ticketGuestIdentity(eventId = eventId, ticketCodeSha256 = ticketCodeSha256)
            } else {
                EVENT_GUEST_IDENTITY_PREFIX + Uuid.random()
            }
        val roomInfo =
            try {
                liveKit.listRooms().firstOrNull { it.name == room.livekitRoomName }
            } catch (e: LiveKitAdminException) {
                null
            }
        val present =
            try {
                liveKit.listParticipants(room.livekitRoomName)
            } catch (e: LiveKitAdminException) {
                logger.warn { "event guest join: LiveKit not reachable (${e::class.simpleName})" }
                return EventGuestJoinResponseDto(outcome = EventOnlineOutcome.UNAVAILABLE)
            }
        val roomRow =
            transaction { ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq room.roomId }.single() }
        if (roomRow[ConferenceRoomTable.endedAt] != null) return EventGuestJoinResponseDto(outcome = EventOnlineOutcome.ENDED)
        val window = checkNotNull(decision.window)
        val ttl = tokenTtl(isGuest = true, window = window, now = now)
        // Check the seats and reserve this guest atomically (no suspension point in between): connected AND still-pending guests count.
        val reserved =
            reserveGuestSeat(
                roomName = room.livekitRoomName,
                identity = identity,
                present = present.map { it.identity }.toSet(),
                maxSeats = roomInfo?.maxParticipants ?: 0,
                taken = maxOf(roomInfo?.numParticipants ?: 0, present.size),
                expiresAt = now + ttl,
                now = now,
            )
        if (!reserved) return EventGuestJoinResponseDto(outcome = EventOnlineOutcome.FULL)
        val minted =
            LiveKitAccessToken.mintParticipantToken(
                apiKey = config.apiKey,
                apiSecret = config.apiSecret,
                roomName = room.livekitRoomName,
                identity = identity,
                displayName = displayName,
                ttl = ttl,
                canPublish = true,
                canPublishData = true,
                microphoneAllowed = roomRow[ConferenceRoomTable.participantsMayUnmute],
                now = now,
            )
        return EventGuestJoinResponseDto(
            outcome = EventOnlineOutcome.JOINED,
            serverUrl = config.livekitUrl,
            token = minted.jwt,
            identity = identity,
            displayName = displayName,
            expiresAt = minted.expiresAt.toString(),
            turnServers = turnServers(label = identity, ttl = ttl, now = now),
        )
    }

    /**
     * Seat check + reservation for an account-less guest. Seats stay reserved for members and moderators: guests may neither fill the
     * room nor exceed their own cap. Every guest connected in LiveKit and every minted, not yet expired token counts (even after its holder disconnected).
     * A re-join of an identity that already holds a seat (connected or pending) takes no additional one.
     */
    private fun reserveGuestSeat(
        roomName: String,
        identity: String,
        present: Set<String>,
        maxSeats: Int,
        taken: Int,
        expiresAt: Instant,
        now: Instant,
    ): Boolean =
        synchronized(pendingGuests) {
            pendingGuests.entries.removeIf { (_, ledger) ->
                ledger.values.removeIf { it <= now }
                ledger.isEmpty()
            }
            val ledger = pendingGuests.getOrPut(roomName) { HashMap() }
            // A minted token stays valid (LiveKit checks expiry only at join) even after its holder disconnects, so every minted identity
            // keeps its seat until the token expires: connecting and disconnecting must not free it for a fresh batch.
            val pendingNotPresent = ledger.keys.count { it !in present }
            val holdsSeat = identity in present || identity in ledger
            if (!holdsSeat) {
                val guestSeats = if (maxSeats > 0) maxSeats - maxSeats / GUEST_RESERVED_SEATS_DIVISOR else Int.MAX_VALUE
                val guestsHeld = (present.filter { isEventGuestIdentity(it) }.toSet() + ledger.keys).size
                if (taken + pendingNotPresent >= guestSeats || guestsHeld >= config.maxNonMemberParticipants) {
                    if (ledger.isEmpty()) pendingGuests.remove(roomName)
                    return@synchronized false
                }
            }
            ledger[identity] = expiresAt
            true
        }

    /** The account-less participants currently in [eventId]'s conference. Caller must have passed [requireEventModerator]. */
    suspend fun listGuests(eventId: Uuid): List<EventGuestParticipantDto> {
        val roomName = activeRoomName(eventId) ?: return emptyList()
        val participants =
            try {
                liveKit.listParticipants(roomName)
            } catch (e: LiveKitAdminException) {
                throw ConflictException("LiveKit request failed")
            }
        return participants
            .filter { it.identity.startsWith(EVENT_GUEST_IDENTITY_PREFIX) }
            .map { EventGuestParticipantDto(identity = it.identity, displayName = it.name) }
    }

    /** Removes one account-less participant. [identity] must be an `evg-` identity (a member is removed through the ordinary room RPC). */
    suspend fun removeGuest(
        eventId: Uuid,
        identity: String,
    ) {
        if (!identity.startsWith(EVENT_GUEST_IDENTITY_PREFIX) || identity.length > MAX_IDENTITY_LENGTH) {
            throw BadRequestException("Only a guest without an account can be removed here")
        }
        val roomName = activeRoomName(eventId) ?: return
        try {
            liveKit.removeParticipant(room = roomName, identity = identity)
        } catch (e: LiveKitAdminException) {
            throw ConflictException("LiveKit request failed")
        }
    }

    private fun activeRoomName(eventId: Uuid): String? =
        transaction {
            val conference = EventConferenceStore.get(eventId) ?: return@transaction null
            val roomId = conference[EventConferenceTable.conferenceRoomId] ?: return@transaction null
            ConferenceRoomTable
                .selectAll()
                .where { ConferenceRoomTable.id eq roomId }
                .singleOrNull()
                ?.takeIf { it[ConferenceRoomTable.endedAt] == null }
                ?.get(ConferenceRoomTable.livekitRoomName)
        }

    /** `true` iff LiveKit already holds as many participants as the room allows (LiveKit enforces the ceiling itself as well). */
    private suspend fun isFull(livekitRoomName: String): Boolean =
        try {
            liveKit.listRooms().firstOrNull { it.name == livekitRoomName }?.let {
                it.maxParticipants > 0 &&
                    it.numParticipants >= it.maxParticipants
            }
                ?: false
        } catch (e: LiveKitAdminException) {
            false
        }

    private fun notJoined(
        decision: EventOnlineDecision,
        eventRow: ResultRow?,
        orgZone: TimeZone,
    ): EventOnlineJoinResultDto =
        EventOnlineJoinResultDto(
            outcome = decision.outcome,
            opensAt =
                if (decision.outcome == EventOnlineOutcome.NOT_YET && eventRow != null) {
                    EventOnlineWindow.wallOpensAt(startsAt = eventRow[EventTable.startsAt], orgZone = orgZone)
                } else {
                    null
                },
        )

    private fun encounterResult(eventRow: ResultRow?): EventOnlineJoinResultDto {
        val spaceId =
            eventRow?.get(EventTable.encounterSpaceId) ?: return EventOnlineJoinResultDto(outcome = EventOnlineOutcome.UNAVAILABLE)
        val open =
            transaction {
                EncounterSpaceTable
                    .selectAll()
                    .where { (EncounterSpaceTable.id eq spaceId) and EncounterSpaceTable.archivedAt.isNull() }
                    .any()
            }
        return if (open) {
            EventOnlineJoinResultDto(outcome = EventOnlineOutcome.JOINED, encounterSpaceId = spaceId.toString())
        } else {
            EventOnlineJoinResultDto(outcome = EventOnlineOutcome.UNAVAILABLE)
        }
    }

    private fun requireGuestNotice(consent: ConferenceGuestConsentAcknowledgmentInput?) {
        val given = consent ?: throw ConflictException("Das Gast-Hinweis muss bestätigt werden.")
        if (given.consentVersion != EventGuestNotice.VERSION || given.consentSha256 != EventGuestNotice.SHA256) {
            throw ConflictException("Der Gast-Hinweis ist nicht mehr aktuell -- bitte erneut bestätigen.")
        }
    }

    /** Members get the configured lifetime, a guest the short one; neither outlives the window. */
    private fun tokenTtl(
        isGuest: Boolean,
        window: EventOnlineWindow.Window,
        now: Instant,
    ): Duration {
        val configured = (if (isGuest) config.guestTokenTtlMinutes else config.tokenTtlMinutes).minutes
        return minOf(configured, window.closesAt - now).coerceAtLeast(MIN_TOKEN_TTL)
    }

    private fun turnServers(
        label: String,
        ttl: Duration,
        now: Instant,
    ): List<ConferenceTurnServer> {
        if (!config.turnEnabled) return emptyList()
        val credential =
            TurnCredentialMinter.mint(
                sharedSecret = config.turnSharedSecret,
                urls = config.allTurnUrls,
                label = label,
                ttl = ttl,
                now = now,
            )
        return listOf(ConferenceTurnServer(urls = credential.urls, username = credential.username, credential = credential.credential))
    }

    private fun freshStatus(memberId: Uuid): MemberStatus =
        MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.status]

    private data class MemberPrep(
        val displayName: String,
        val microphoneAllowed: Boolean,
    )

    companion object {
        private const val MAX_IDENTITY_LENGTH = 100

        /** A quarter of a room's seats is never handed to account-less guests, so members and moderators can still get in. */
        private const val GUEST_RESERVED_SEATS_DIVISOR = 4

        /**
         * The stable `evg-` identity of a ticket: a SHA-256 over a domain separator, the event and the ticket hash (the hash itself is never
         * exposed to the other participants, who can see identities). 32 hex characters keep the identity well below [MAX_IDENTITY_LENGTH].
         */
        internal fun ticketGuestIdentity(
            eventId: Uuid,
            ticketCodeSha256: String,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("evg-ticket|$eventId|$ticketCodeSha256".toByteArray(Charsets.UTF_8))
            return EVENT_GUEST_IDENTITY_PREFIX + digest.take(16).joinToString("") { "%02x".format(it) }
        }

        /**
         * Normalizes a typed guest name: NFC, control and format characters (incl. bidi overrides) removed, whitespace collapsed and trimmed,
         * 1..[EVENT_GUEST_NAME_MAX_LENGTH] characters. Returns `null` for an unusable name. Does NOT append [EVENT_GUEST_NAME_SUFFIX].
         */
        fun normalizeGuestName(raw: String): String? {
            val nfc = Normalizer.normalize(raw, Normalizer.Form.NFC)
            val cleaned =
                buildString {
                    nfc.codePoints().forEach { cp ->
                        // tabs, line breaks and every kind of space separator become ONE ordinary space first (a control character would otherwise glue words together)
                        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                            append(' ')
                            return@forEach
                        }
                        val type = Character.getType(cp)
                        val drop =
                            type == Character.CONTROL.toInt() ||
                                type == Character.FORMAT.toInt() ||
                                type == Character.LINE_SEPARATOR.toInt() ||
                                type == Character.PARAGRAPH_SEPARATOR.toInt() ||
                                type == Character.PRIVATE_USE.toInt() ||
                                type == Character.SURROGATE.toInt() ||
                                type == Character.UNASSIGNED.toInt()
                        if (!drop) appendCodePoint(cp)
                    }
                }.replace(Regex("\\s+"), " ").trim()
            if (cleaned.isEmpty() || cleaned.codePointCount(0, cleaned.length) > EVENT_GUEST_NAME_MAX_LENGTH) return null
            return cleaned
        }

        /** The moderator rule: the organizer, or BOARD/ADMIN. */
        fun requireEventModerator(
            eventRow: ResultRow?,
            current: CurrentMember,
        ) {
            val organizer = eventRow != null && eventRow[EventTable.createdBy] == current.memberId
            if (!organizer && !current.isPrivileged) throw ForbiddenException()
        }
    }
}
