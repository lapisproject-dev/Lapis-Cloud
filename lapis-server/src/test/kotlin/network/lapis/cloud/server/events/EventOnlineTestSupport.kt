package network.lapis.cloud.server.events

import kotlinx.coroutines.CompletableDeferred
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.conference.LiveKitParticipantInfo
import network.lapis.cloud.server.conference.LiveKitParticipantPermissionUpdate
import network.lapis.cloud.server.conference.LiveKitRoomInfo
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ConferenceGuestConsentAcknowledgmentTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.server.db.generated.EventConferenceTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventSeriesTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** `enabled = true`, the LiveKit side is [EventFakeLiveKit]. The event ceiling is 100 (the default), the non-member ceiling 20. */
internal val EVENT_ONLINE_CONFIG: ConferenceConfig =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            else -> null
        }
    }

/**
 * Hermetic LiveKit stand-in for the event conference tests: counts the `createRoom` calls (the "exactly once" proof), can hold a
 * `createRoom` open ([createGate], the race tests) or fail it ([failCreate]), and keeps what it was asked to create.
 */
internal class EventFakeLiveKit : LiveKitAdminClient {
    val rooms = ConcurrentHashMap<String, LiveKitRoomInfo>()
    val participants = ConcurrentHashMap<String, MutableList<LiveKitParticipantInfo>>()
    val createCalls = AtomicInteger()
    val deleted = CopyOnWriteArrayList<String>()
    val removed = CopyOnWriteArrayList<Pair<String, String>>()
    val createdParams = CopyOnWriteArrayList<Pair<Int, Int>>()

    /** While non-null every `createRoom` suspends until it completes. */
    @Volatile var createGate: CompletableDeferred<Unit>? = null

    @Volatile var failCreate = false

    @Volatile var failList = false

    override suspend fun createRoom(
        name: String,
        maxParticipants: Int,
        emptyTimeoutSeconds: Int,
        departureTimeoutSeconds: Int?,
    ): LiveKitRoomInfo {
        createCalls.incrementAndGet()
        createdParams += maxParticipants to emptyTimeoutSeconds
        createGate?.await()
        if (failCreate) throw LiveKitAdminException(message = "simulated LiveKit failure")
        val info =
            LiveKitRoomInfo(
                sid = "RM_$name",
                name = name,
                maxParticipants = maxParticipants,
                numParticipants =
                    participants[name]?.size ?: 0,
            )
        rooms[name] = info
        participants.getOrPut(name) { CopyOnWriteArrayList() }
        return info
    }

    override suspend fun deleteRoom(name: String) {
        deleted += name
        rooms.remove(name)
        participants.remove(name)
    }

    override suspend fun listRooms(): List<LiveKitRoomInfo> {
        if (failList) throw LiveKitAdminException(message = "simulated LiveKit failure")
        return rooms.values.map { it.copy(numParticipants = participants[it.name]?.size ?: 0) }
    }

    override suspend fun listParticipants(room: String): List<LiveKitParticipantInfo> = participants[room].orEmpty().toList()

    override suspend fun removeParticipant(
        room: String,
        identity: String,
    ) {
        removed += room to identity
        participants[room]?.removeIf { it.identity == identity }
    }

    fun seedLive(
        room: String,
        identity: String,
        name: String = identity,
    ) {
        participants.getOrPut(room) { CopyOnWriteArrayList() }.add(LiveKitParticipantInfo(identity = identity, name = name))
    }

    fun forgetRoom(name: String) {
        rooms.remove(name)
    }

    override suspend fun sendData(
        room: String,
        topic: String,
        payload: ByteArray,
    ): Unit = throw UnsupportedOperationException()

    override suspend fun muteMicrophone(
        room: String,
        identity: String,
        trackSid: String,
    ): Unit = throw UnsupportedOperationException()

    override suspend fun updateParticipantPermission(
        room: String,
        identity: String,
        permission: LiveKitParticipantPermissionUpdate,
    ): Unit = throw UnsupportedOperationException()

    override suspend fun sendDataTo(
        room: String,
        topic: String,
        payload: ByteArray,
        destinationIdentities: List<String>,
    ): Unit = throw UnsupportedOperationException()
}

/** Database fixtures of the event-online tests, with a cleanup that removes everything they created (children first). */
internal class EventOnlineFixtures {
    val memberIds = mutableListOf<Uuid>()
    val eventIds = mutableListOf<Uuid>()
    val spaceIds = mutableListOf<Uuid>()

    fun member(
        role: AccountRole = AccountRole.MEMBER,
        status: MemberStatus = MemberStatus.ACTIVE,
        name: String = "Online Test",
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[displayName] = name
                it[email] = "event-online-$id@example.org"
                it[MemberTable.status] = status
                it[joinedAt] = LocalDate(2026, 1, 1)
                it[membershipTierId] = null
            }
            AccountTable.insert {
                it[AccountTable.id] = Uuid.random()
                it[memberId] = id
                it[AccountTable.role] = role
            }
        }
        memberIds += id
        return id
    }

    fun current(
        memberId: Uuid,
        role: AccountRole = AccountRole.MEMBER,
        status: MemberStatus = MemberStatus.ACTIVE,
    ): CurrentMember = CurrentMember(memberId = memberId, role = role, status = status)

    fun space(
        archived: Boolean = false,
        membersOnly: Boolean = false,
    ): Uuid {
        val creator = member(role = AccountRole.ADMIN)
        val id = Uuid.random()
        val now = DbClock.nowLocalDateTime()
        transaction {
            EncounterSpaceTable.insert {
                it[EncounterSpaceTable.id] = id
                it[title] = "Raum $id"
                it[description] = ""
                it[themeKey] = "CHURCH"
                it[mode] = "SERVICE"
                it[guestPolicy] = if (membersOnly) "MEMBERS_ONLY" else "MEMBERS_AND_GUESTS"
                it[maxParticipants] = null
                it[closedNotice] = null
                it[createdAt] = now
                it[createdByMemberId] = creator
                it[updatedAt] = null
                it[archivedAt] = if (archived) now else null
            }
        }
        spaceIds += id
        return id
    }

    fun event(
        createdBy: Uuid,
        kind: EventOnlineKind = EventOnlineKind.LAPIS_CONFERENCE,
        startsAt: LocalDateTime = LocalDateTime(2030, 6, 15, 18, 0),
        endsAt: LocalDateTime = LocalDateTime(2030, 6, 15, 20, 0),
        visibility: EventVisibility = EventVisibility.PUBLIC,
        status: EventStatus = EventStatus.PUBLISHED,
        registrationClosesAt: LocalDateTime? = null,
        capacity: Int? = null,
        encounterSpaceId: Uuid? = null,
        locationText: String? = "Saal",
        title: String = "Online-Event",
    ): Uuid {
        val id = Uuid.random()
        transaction {
            EventTable.insert {
                it[EventTable.id] = id
                it[slug] = "event-online-$id"
                it[EventTable.title] = title
                it[description] = "test"
                it[EventTable.locationText] = locationText
                it[onlineUrl] = null
                it[onlineKind] = kind
                it[EventTable.encounterSpaceId] = encounterSpaceId
                it[EventTable.startsAt] = startsAt
                it[EventTable.endsAt] = endsAt
                it[EventTable.capacity] = capacity
                it[feeAmount] = BigDecimal.ZERO
                it[feeCurrency] = "EUR"
                it[EventTable.status] = status
                it[EventTable.visibility] = visibility
                it[EventTable.registrationClosesAt] = registrationClosesAt
                it[createdAt] = DbClock.nowLocalDateTime()
                it[EventTable.createdBy] = createdBy
                it[cancelledAt] = null
            }
        }
        eventIds += id
        return id
    }

    fun registration(
        eventId: Uuid,
        memberId: Uuid? = null,
        status: EventRegistrationStatus = EventRegistrationStatus.CONFIRMED,
        ticketHash: String? = null,
    ): Uuid {
        val id = Uuid.random()
        val now = DbClock.nowLocalDateTime()
        transaction {
            EventRegistrationTable.insert {
                it[EventRegistrationTable.id] = id
                it[EventRegistrationTable.eventId] = eventId
                it[EventRegistrationTable.memberId] = memberId
                it[guestName] = if (memberId == null) "Gast" else null
                it[guestEmail] = if (memberId == null) "gast-$id@example.org" else null
                val inactive = status == EventRegistrationStatus.CANCELLED || status == EventRegistrationStatus.EXPIRED
                it[activeParticipantKey] =
                    if (inactive) {
                        null
                    } else if (memberId != null) {
                        "m:$memberId"
                    } else {
                        "g:gast-$id@example.org"
                    }
                it[EventRegistrationTable.status] = status
                it[feeAmount] = BigDecimal.ZERO
                it[holdExpiresAt] = if (status == EventRegistrationStatus.PENDING_PAYMENT) now.plus30Minutes() else null
                it[waitlistPosition] = if (status == EventRegistrationStatus.WAITLISTED) 1 else null
                it[cancelTokenSha256] = null
                it[registeredAt] = now
                it[confirmedAt] = if (status == EventRegistrationStatus.CONFIRMED) now else null
                it[cancelledAt] = if (inactive) now else null
                it[waitlistOfferedAt] = null
                it[ticketCodeSha256] = ticketHash
                it[ticketIssuedAt] = if (ticketHash != null) now else null
            }
        }
        return id
    }

    fun cleanUp() {
        transaction {
            if (eventIds.isNotEmpty()) {
                EventConferenceTable.deleteWhere { eventId inList eventIds }
            }
            // rooms of the events -- also the ones whose bookkeeping row was reset (a rescheduled event keeps the old room as history)
            val roomIds =
                if (memberIds.isEmpty()) {
                    emptyList()
                } else {
                    ConferenceRoomTable
                        .selectAll()
                        .where {
                            ConferenceRoomTable.createdByMemberId inList memberIds
                        }.map { it[ConferenceRoomTable.id] }
                }
            if (roomIds.isNotEmpty()) {
                ConferenceGuestConsentAcknowledgmentTable.deleteWhere { roomId inList roomIds }
                ConferenceParticipationTable.deleteWhere { roomId inList roomIds }
                ConferenceRoomTable.deleteWhere { id inList roomIds }
            }
            if (eventIds.isNotEmpty()) {
                EventRegistrationTable.deleteWhere { eventId inList eventIds }
                EventTable.deleteWhere { id inList eventIds }
            }
            if (memberIds.isNotEmpty()) EventSeriesTable.deleteWhere { createdBy inList memberIds }
            if (spaceIds.isNotEmpty()) EncounterSpaceTable.deleteWhere { id inList spaceIds }
            if (memberIds.isNotEmpty()) {
                ConferenceParticipationTable.deleteWhere { memberId inList memberIds }
                ConferenceGuestConsentAcknowledgmentTable.deleteWhere { memberId inList memberIds }
                AccountTable.deleteWhere { memberId inList memberIds }
                MemberTable.deleteWhere { id inList memberIds }
            }
        }
    }

    /** All `conference_room` rows that belong to [eventId] (normally zero or one). */
    fun roomIdsOf(eventId: Uuid): List<Uuid> =
        transaction {
            EventConferenceTable
                .selectAll()
                .where { EventConferenceTable.eventId eq eventId }
                .mapNotNull { it[EventConferenceTable.conferenceRoomId] }
        }
}

private fun LocalDateTime.plus30Minutes(): LocalDateTime = toJavaLocalDateTime().plusMinutes(30).toKotlinLocalDateTime()
