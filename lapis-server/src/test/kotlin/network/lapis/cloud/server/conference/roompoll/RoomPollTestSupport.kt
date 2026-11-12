package network.lapis.cloud.server.conference.roompoll

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ModerationFakeLiveKit
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollAnswerTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollOptionTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollTable
import network.lapis.cloud.server.db.generated.ConferenceRoomPollVotedTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.EventConferenceStatus
import network.lapis.cloud.server.db.generated.EventConferenceTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.events.EventOnlineFixtures
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.ConferenceRoomPollService
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RoomPollStartInput
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

internal val POLL_CONFIG =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            "LAPIS_LIVEKIT_TOKEN_TTL_MINUTES" -> "240"
            "LAPIS_CONFERENCE_MAX_PARTICIPANTS" -> "25"
            else -> null
        }
    }

internal fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

/** The collaborators of a [ConferenceRoomPollService] under test. */
internal class PollRig(
    val liveKit: ModerationFakeLiveKit = ModerationFakeLiveKit(),
    val write: FederationInboxRateLimiter = generousLimiter(),
    val answer: FederationInboxRateLimiter = generousLimiter(),
    val state: FederationInboxRateLimiter = generousLimiter(),
    val version: FederationInboxRateLimiter = generousLimiter(),
) {
    fun service(call: io.ktor.server.application.ApplicationCall) =
        ConferenceRoomPollService(
            call = call,
            liveKitAdminClient = liveKit,
            config = POLL_CONFIG,
            ltrBalanceProvider = LedgerBackedLtrBalanceProvider(),
            writeLimiter = write,
            answerLimiter = answer,
            stateLimiter = state,
            versionLimiter = version,
        )

    fun nudges() = liveKit.packets.filter { it.topic == network.lapis.cloud.shared.domain.ROOM_POLL_TOPIC }
}

internal class PollRoom(
    val id: Uuid,
    val name: String,
    val creator: Uuid,
)

/** Throwaway members, rooms, participations, streams and LTR; removes exactly what it created. */
internal class RoomPollFixtures {
    val memberIds = mutableListOf<Uuid>()
    val roomIds = mutableListOf<Uuid>()
    private val events = EventOnlineFixtures()

    /** A room that belongs to a permanent encounter space (no room poll there). */
    fun encounterRoom(creator: Uuid): PollRoom {
        val room = room(creator = creator)
        val space = events.space()
        transaction { ConferenceRoomTable.update({ ConferenceRoomTable.id eq room.id }) { it[encounterSpaceId] = space } }
        return room
    }

    /** A room that is the conference of an online event (no room poll there). */
    fun eventRoom(creator: Uuid): PollRoom {
        val room = room(creator = creator)
        val event = events.event(createdBy = creator)
        val now = DbClock.nowLocalDateTime()
        transaction {
            EventConferenceTable.insert {
                it[EventConferenceTable.eventId] = event
                it[conferenceRoomId] = room.id
                it[livekitRoomName] = room.name
                it[status] = EventConferenceStatus.ACTIVE
                it[claimToken] = Uuid.random()
                it[claimedAt] = now
                it[windowOpensAt] = LocalDateTime(2030, 6, 15, 17, 45)
                it[windowClosesAt] = LocalDateTime(2030, 6, 15, 21, 0)
                it[createdAt] = now
                it[endedAt] = null
            }
        }
        return room
    }

    fun member(
        status: MemberStatus = MemberStatus.ACTIVE,
        role: AccountRole = AccountRole.MEMBER,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[displayName] = "Raumumfrage ${id.toString().take(8)}"
                it[email] = "roompoll-$id@example.org"
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

    fun setStatus(
        member: Uuid,
        status: MemberStatus,
    ) {
        transaction { MemberTable.update({ MemberTable.id eq member }) { it[MemberTable.status] = status } }
    }

    fun room(
        creator: Uuid,
        ended: Boolean = false,
    ): PollRoom {
        val id = Uuid.random()
        val name = "lc-$id"
        transaction {
            ConferenceRoomTable.insert {
                it[ConferenceRoomTable.id] = id
                it[title] = "Umfrageraum"
                it[description] = ""
                it[livekitRoomName] = name
                it[createdByMemberId] = creator
                it[createdAt] = DbClock.nowLocalDateTime()
                it[endedAt] = if (ended) DbClock.nowLocalDateTime() else null
                it[maxParticipants] = 25
                it[allowFederationGuests] = true
            }
        }
        roomIds += id
        return PollRoom(id = id, name = name, creator = creator)
    }

    fun join(
        room: PollRoom,
        who: Uuid,
    ) {
        transaction {
            ConferenceParticipationTable.insert {
                it[ConferenceParticipationTable.id] = Uuid.random()
                it[roomId] = room.id
                it[memberId] = who
                it[role] = ConferenceRole.PARTICIPANT
                it[joinedAt] = DbClock.nowLocalDateTime()
                it[leftAt] = null
            }
        }
    }

    fun stream(
        room: PollRoom,
        starter: Uuid,
        status: ConferenceStreamStatus = ConferenceStreamStatus.LIVE,
    ) {
        transaction {
            ConferenceStreamTable.insert {
                it[ConferenceStreamTable.id] = Uuid.random()
                it[roomId] = room.id
                it[startedByMemberId] = starter
                it[ConferenceStreamTable.status] = status
                it[layout] = ConferenceStreamLayout.GRID
                it[latencyMode] = ConferenceStreamLatencyMode.STANDARD
                it[startedAt] = DbClock.nowLocalDateTime()
                it[restartCount] = 0
            }
        }
    }

    fun endRoom(room: PollRoom) {
        transaction { ConferenceRoomTable.update({ ConferenceRoomTable.id eq room.id }) { it[endedAt] = DbClock.nowLocalDateTime() } }
    }

    fun grant(
        member: Uuid,
        amount: String,
        at: LocalDateTime = DbClock.nowLocalDateTime(),
    ) {
        transaction {
            LtrLedgerEntryTable.insert {
                it[id] = Uuid.random()
                it[memberId] = member
                it[entryType] = LtrLedgerEntryType.MINT
                it[amountLtr] = BigDecimal(amount)
                it[referenceType] = null
                it[referenceId] = null
                it[note] = "room poll test"
                it[createdBy] = null
                it[createdAt] = at
            }
        }
    }

    fun ledgerRowCount(member: Uuid): Int =
        transaction {
            LtrLedgerEntryTable
                .selectAll()
                .where { LtrLedgerEntryTable.memberId eq member }
                .count()
                .toInt()
        }

    fun polls(room: PollRoom) =
        transaction { ConferenceRoomPollTable.selectAll().where { ConferenceRoomPollTable.roomId eq room.id }.toList() }

    fun answers(poll: Uuid) =
        transaction {
            ConferenceRoomPollAnswerTable.selectAll().where { ConferenceRoomPollAnswerTable.pollId eq poll }.toList()
        }

    fun markers(poll: Uuid) =
        transaction {
            ConferenceRoomPollVotedTable.selectAll().where { ConferenceRoomPollVotedTable.pollId eq poll }.toList()
        }

    fun audits(poll: Uuid) =
        transaction {
            AuditLogEntryTable
                .selectAll()
                .where { AuditLogEntryTable.entityId eq poll }
                .orderBy(AuditLogEntryTable.sequenceNumber)
                .toList()
        }

    /** Moves the start of a poll into the past, to make it due by the 240 minute limit. */
    fun backdate(
        poll: Uuid,
        minutes: Long,
    ) {
        val past = (DbClock.nowLocalDateTime().toInstant(TimeZone.UTC) - minutes.minutes).toLocalDateTime(TimeZone.UTC)
        transaction { ConferenceRoomPollTable.update({ ConferenceRoomPollTable.id eq poll }) { it[startedAt] = past } }
    }

    fun wipePolls() {
        transaction {
            ConferenceRoomPollAnswerTable.deleteWhere { ConferenceRoomPollAnswerTable.pollId inList allPollIds() }
            ConferenceRoomPollVotedTable.deleteWhere { ConferenceRoomPollVotedTable.pollId inList allPollIds() }
            ConferenceRoomPollOptionTable.deleteWhere { ConferenceRoomPollOptionTable.pollId inList allPollIds() }
            ConferenceRoomPollTable.deleteWhere { ConferenceRoomPollTable.roomId inList roomIds }
        }
    }

    private fun allPollIds(): List<Uuid> =
        ConferenceRoomPollTable.selectAll().where { ConferenceRoomPollTable.roomId inList roomIds }.map { it[ConferenceRoomPollTable.id] }

    fun cleanUp() {
        wipePolls()
        transaction {
            ConferenceStreamTable.deleteWhere { ConferenceStreamTable.roomId inList roomIds }
            ConferenceParticipationTable.deleteWhere {
                (ConferenceParticipationTable.roomId inList roomIds) or (ConferenceParticipationTable.memberId inList memberIds)
            }
            EventConferenceTable.deleteWhere { EventConferenceTable.conferenceRoomId inList roomIds }
            ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id inList roomIds }
            events.cleanUp()
            LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList memberIds }
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) { it[actorMemberId] = null }
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
    }
}

internal fun pollInput(
    room: PollRoom,
    question: String = "Sollen wir verlängern?",
    options: List<String> = listOf("Ja", "Nein", "Enthaltung"),
    mode: network.lapis.cloud.shared.domain.RoomPollCountingMode = network.lapis.cloud.shared.domain.RoomPollCountingMode.BOTH,
) = RoomPollStartInput(roomId = room.id.toString(), question = question, options = options, countingMode = mode)
