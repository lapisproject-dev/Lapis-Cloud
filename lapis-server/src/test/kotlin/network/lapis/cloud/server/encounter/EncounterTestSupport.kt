package network.lapis.cloud.server.encounter

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.conference.LiveKitParticipantInfo
import network.lapis.cloud.server.conference.LiveKitRoomInfo
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceGuestConsentAcknowledgmentTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EncounterConsentAcknowledgmentTable
import network.lapis.cloud.server.db.generated.EncounterSpaceRoleTable
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.mail.FakeEncounterEntryNoticeMailer
import network.lapis.cloud.server.rpc.EncounterSpaceService
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EncounterGuestPolicy
import network.lapis.cloud.shared.domain.EncounterNotifyMode
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterReactionOption
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** A [ConferenceConfig] with `enabled = true` and the production defaults (25 participants, 20 non-members). */
internal val ENCOUNTER_CONFIG: ConferenceConfig =
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

/** A small instance: 6 participants, at most 2 non-members -- makes the ceilings reachable in a test. */
internal val ENCOUNTER_CONFIG_SMALL: ConferenceConfig =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            "LAPIS_CONFERENCE_MAX_PARTICIPANTS" -> "6"
            "LAPIS_CONFERENCE_MAX_NON_MEMBER_PARTICIPANTS" -> "2"
            else -> null
        }
    }

/** `enabled = false`. */
internal val ENCOUNTER_CONFIG_DISABLED: ConferenceConfig = ConferenceConfig.load { null }

/** Hermetic, thread-safe stand-in for [LiveKitAdminClient]: no LiveKit container, with call logs and failure injection. */
internal class FakeEncounterLiveKit : LiveKitAdminClient {
    private val rooms = ConcurrentHashMap<String, LiveKitRoomInfo>()
    private val live = ConcurrentHashMap<String, MutableSet<String>>()
    val createdRooms: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val deletedRooms: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    val removed: MutableList<Pair<String, String>> = java.util.Collections.synchronizedList(mutableListOf())
    val createRoomArgs: MutableList<Triple<String, Int, Int>> = java.util.Collections.synchronizedList(mutableListOf())

    val createRoomDepartureTimeouts: MutableList<Int?> = java.util.Collections.synchronizedList(mutableListOf())

    @Volatile var failAll = false

    @Volatile var failDeleteRoom = false

    @Volatile var failRemoveParticipant = false

    /** V1.9.95: every `sendData` call (room, topic, payload), in order. */
    val sentData: MutableList<Triple<String, String, ByteArray>> = java.util.Collections.synchronizedList(mutableListOf())

    @Volatile var failSendData = false

    override suspend fun sendData(
        room: String,
        topic: String,
        payload: ByteArray,
    ) {
        if (failSendData || failAll) throw LiveKitAdminException(message = "simulated SendData failure")
        sentData += Triple(room, topic, payload)
    }

    /** Milliseconds `createRoom` sleeps -- widens the race window of a concurrent open. */
    @Volatile var createDelayMillis = 0L

    override suspend fun createRoom(
        name: String,
        maxParticipants: Int,
        emptyTimeoutSeconds: Int,
        departureTimeoutSeconds: Int?,
    ): LiveKitRoomInfo {
        if (failAll) throw LiveKitAdminException(message = "simulated LiveKit failure")
        if (createDelayMillis > 0) kotlinx.coroutines.delay(createDelayMillis)
        createdRooms += name
        createRoomArgs += Triple(name, maxParticipants, emptyTimeoutSeconds)
        createRoomDepartureTimeouts += departureTimeoutSeconds
        val info = LiveKitRoomInfo(sid = "RM_$name", name = name, maxParticipants = maxParticipants)
        rooms[name] = info
        live.getOrPut(name) {
            java.util.concurrent.ConcurrentHashMap
                .newKeySet()
        }
        return info
    }

    override suspend fun deleteRoom(name: String) {
        if (failAll || failDeleteRoom) throw LiveKitAdminException(message = "simulated LiveKit failure")
        deletedRooms += name
        rooms.remove(name)
        live.remove(name)
    }

    override suspend fun listRooms(): List<LiveKitRoomInfo> {
        if (failAll) throw LiveKitAdminException(message = "simulated LiveKit failure")
        return rooms.values.toList()
    }

    override suspend fun listParticipants(room: String): List<LiveKitParticipantInfo> {
        if (failAll) throw LiveKitAdminException(message = "simulated LiveKit failure")
        return live[room].orEmpty().map {
            LiveKitParticipantInfo(identity = it, name = it, permission = permissions[room to it], tracks = tracks[room to it].orEmpty())
        }
    }

    override suspend fun removeParticipant(
        room: String,
        identity: String,
    ) {
        if (failAll || failRemoveParticipant) throw LiveKitAdminException(message = "simulated LiveKit failure")
        removed += room to identity
        live[room]?.remove(identity)
    }

    /** Grants LiveKit reports per connected (room, identity); absent = `null` permission like an unreported one. */
    val permissions =
        java.util.concurrent
            .ConcurrentHashMap<Pair<String, String>, network.lapis.cloud.server.conference.LiveKitParticipantPermission>()

    /** Tracks LiveKit reports per connected (room, identity) -- empty by default. */
    val tracks =
        java.util.concurrent
            .ConcurrentHashMap<Pair<String, String>, List<network.lapis.cloud.server.conference.LiveKitTrackInfo>>()

    /** Connects [identity] with the grants of its token (what a reconnect with an OLD token looks like). */
    fun connect(
        room: String,
        identity: String,
        canPublish: Boolean,
        canPublishData: Boolean,
    ) {
        connect(room = room, identity = identity)
        permissions[room to identity] =
            network.lapis.cloud.server.conference
                .LiveKitParticipantPermission(canPublish = canPublish, canPublishData = canPublishData)
    }

    fun connect(
        room: String,
        identity: String,
    ) {
        live
            .getOrPut(room) {
                java.util.concurrent.ConcurrentHashMap
                    .newKeySet()
            }.add(identity)
    }

    fun forgetParticipant(
        room: String,
        identity: String,
    ) {
        live[room]?.remove(identity)
    }

    fun hasRoom(name: String): Boolean = rooms.containsKey(name)

    fun forgetRoom(name: String) {
        rooms.remove(name)
        live.remove(name)
    }

    fun isConnected(
        room: String,
        identity: String,
    ): Boolean = live[room]?.contains(identity) == true
}

/** Every throttle and the moderation state of one test, constructed ONCE (the service is built per request, see its KDoc). */
internal class EncounterRig(
    val liveKit: FakeEncounterLiveKit = FakeEncounterLiveKit(),
    val config: ConferenceConfig = ENCOUNTER_CONFIG,
    val moderationState: EncounterModerationState = EncounterModerationState(),
    /** V1.9.95: the in-memory blessing throttle -- ONE per test. */
    val blessingState: EncounterBlessingState = EncounterBlessingState(),
    /** V1.9.96: the in-memory bell throttle -- ONE per test. */
    val bellState: EncounterBellState = EncounterBellState(),
    /** V1.9.79: the in-memory seat plan -- ONE per test, like the moderation state. */
    val seatState: EncounterSeatState = EncounterSeatState(),
    /** V1.9.79: `null` = as permissive as every other throttle; a test of the 1-per-second throttle passes its own. */
    seatLimiterOverride: FederationInboxRateLimiter? = null,
    /** V1.9.80: the in-memory table plan -- ONE per test. */
    val tableState: EncounterTableState = EncounterTableState(),
    /** V1.9.80: `null` = permissive; a throttle test passes its own. */
    tableLimiterOverride: FederationInboxRateLimiter? = null,
    tableTokenLimiterOverride: FederationInboxRateLimiter? = null,
    private val limiter: () -> FederationInboxRateLimiter = { FederationInboxRateLimiter(maxRequests = 1_000, window = 1.minutes) },
) {
    /** V1.9.76: the entry notice -- ONE state and ONE fake mailer per test, like the moderation state. */
    val entryMailer = FakeEncounterEntryNoticeMailer()
    val entryState = EncounterEntryNoticeState()
    val entryNotifier = EncounterEntryNotifier(state = entryState, mailer = entryMailer)

    private val list = limiter()
    private val enter = limiter()
    private val leave = limiter()
    private val openClose = limiter()
    private val moderation = limiter()
    private val configLimiter = limiter()
    private val seatLimiter = seatLimiterOverride ?: limiter()
    private val tableLimiter = tableLimiterOverride ?: limiter()
    private val tableTokenLimiter = tableTokenLimiterOverride ?: limiter()

    fun service(
        call: ApplicationCall,
        config: ConferenceConfig = this.config,
    ) = EncounterSpaceService(
        call = call,
        liveKitAdminClient = liveKit,
        moderationState = moderationState,
        blessingState = blessingState,
        bellState = bellState,
        seatState = seatState,
        tableState = tableState,
        entryNotifier = entryNotifier,
        listRateLimiter = list,
        enterRateLimiter = enter,
        leaveRateLimiter = leave,
        openCloseRateLimiter = openClose,
        moderationRateLimiter = moderation,
        configRateLimiter = configLimiter,
        seatRateLimiter = seatLimiter,
        tableRateLimiter = tableLimiter,
        tableTokenRateLimiter = tableTokenLimiter,
        config = config,
    )

    /** Runs [block] as [member] against a fresh [EncounterSpaceService] inside the test application behind [client]. */
    suspend fun <T> asMember(
        client: HttpClient,
        member: Uuid,
        block: suspend (EncounterSpaceService) -> T,
    ): Result<T> = client.runAs(member = member) { call -> block(service(call = call)) }
}

private val pendingActions = ConcurrentHashMap<String, suspend (ApplicationCall) -> Any?>()
private val finishedActions = ConcurrentHashMap<String, Result<Any?>>()

/** One test application with a single `/run` route that executes an action registered by [runAs] with the request's [ApplicationCall]. */
internal fun encounterApp(block: suspend ApplicationTestBuilder.() -> Unit) {
    testApplication {
        application {
            routing {
                post("/run") {
                    val id = call.request.headers["X-Action"]!!
                    val action = pendingActions.remove(id)!!
                    finishedActions[id] = runCatching { action(call) }
                    call.respondText("ok")
                }
            }
        }
        block()
    }
}

/** Executes [block] with the [ApplicationCall] of an authenticated (trusted-header test mode) request made as [member]. */
@Suppress("UNCHECKED_CAST")
internal suspend fun <T> HttpClient.runAs(
    member: Uuid,
    block: suspend (ApplicationCall) -> T,
): Result<T> {
    val id = Uuid.random().toString()
    pendingActions[id] = block
    post("/run") {
        header("X-Member-Id", member.toString())
        header("X-Action", id)
    }
    return finishedActions.remove(id) as Result<T>
}

internal inline fun <reified E : Throwable> Result<*>.failure(): E {
    val e = exceptionOrNull() ?: error("expected ${E::class.simpleName} but the call succeeded with ${getOrNull()}")
    check(e is E) { "expected ${E::class.simpleName} but got $e" }
    return e
}

/** Rows a test created, deleted in foreign-key order by [cleanUp]. */
internal class EncounterFixtures {
    val memberIds = mutableListOf<Uuid>()
    val spaceIds = mutableListOf<Uuid>()

    fun createMember(
        status: MemberStatus = MemberStatus.ACTIVE,
        role: AccountRole = AccountRole.MEMBER,
        name: String = "Begegnungsraum Testmitglied",
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[displayName] = "$name ${id.toString().take(8)}"
                it[email] = "encounter-$id@example.org"
                it[MemberTable.status] = status
                it[joinedAt] = LocalDate(2026, 1, 1)
                it[membershipTierId] = null
                if (status == MemberStatus.FRIEND) it[friendSince] = LocalDate(2026, 1, 1)
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

    /** Inserts a space directly (the service's own create path is tested separately). */
    fun createSpace(
        createdBy: Uuid,
        title: String = "Gottesdienst",
        guestPolicy: EncounterGuestPolicy = EncounterGuestPolicy.MEMBERS_ONLY,
        maxParticipants: Int? = null,
        archived: Boolean = false,
        profile: EncounterProfile = EncounterProfile.CHURCH_SERVICE,
        reactions: List<EncounterReactionOption> = EncounterReactionOption.defaultsFor(profile),
        notifyMode: EncounterNotifyMode = EncounterNotifyMode.NONE,
        tablesEnabled: Boolean = false,
        tableCount: Int = 4,
        tableSeats: Int = 6,
    ): Uuid {
        val id = Uuid.random()
        val now = DbClock.nowLocalDateTime()
        transaction {
            EncounterSpaceTable.insert {
                it[EncounterSpaceTable.id] = id
                it[EncounterSpaceTable.title] = title
                it[description] = ""
                it[themeKey] = "CHURCH"
                it[EncounterSpaceTable.profile] = profile.name
                it[reactionSet] = reactionSetCsv(reactions)
                it[EncounterSpaceTable.notifyMode] = notifyMode.name
                it[EncounterSpaceTable.tablesEnabled] = tablesEnabled
                it[EncounterSpaceTable.tableCount] = tableCount.toShort()
                it[EncounterSpaceTable.tableSeats] = tableSeats.toShort()
                it[mode] = "SERVICE"
                it[EncounterSpaceTable.guestPolicy] = guestPolicy.name
                it[EncounterSpaceTable.maxParticipants] = maxParticipants
                it[closedNotice] = null
                it[createdAt] = now
                it[createdByMemberId] = createdBy
                it[updatedAt] = null
                it[archivedAt] = if (archived) now else null
            }
        }
        spaceIds += id
        return id
    }

    fun setRole(
        spaceId: Uuid,
        memberId: Uuid,
        role: EncounterSpaceRole,
    ) {
        transaction {
            EncounterSpaceRoleTable.insert {
                it[EncounterSpaceRoleTable.spaceId] = spaceId
                it[EncounterSpaceRoleTable.memberId] = memberId
                it[EncounterSpaceRoleTable.role] = role.name
            }
        }
    }

    fun setStatus(
        memberId: Uuid,
        status: MemberStatus,
    ) {
        transaction { MemberTable.update({ MemberTable.id eq memberId }) { it[MemberTable.status] = status } }
    }

    fun sessionRoomIds(spaceId: Uuid): List<Uuid> =
        transaction {
            ConferenceRoomTable.selectAll().where { ConferenceRoomTable.encounterSpaceId eq spaceId }.map { it[ConferenceRoomTable.id] }
        }

    fun openSessionRoom(spaceId: Uuid): Uuid? =
        transaction {
            ConferenceRoomTable
                .selectAll()
                .where { (ConferenceRoomTable.encounterSpaceId eq spaceId) and ConferenceRoomTable.endedAt.isNull() }
                .map { it[ConferenceRoomTable.id] }
                .singleOrNull()
        }

    fun livekitName(roomId: Uuid): String =
        transaction {
            ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.single()[ConferenceRoomTable.livekitRoomName]
        }

    /** Inserts a session room + a presence row directly, as `openSpace`/`enterSpace` would have left them. */
    fun insertSession(
        spaceId: Uuid,
        openedBy: Uuid,
        endedAt: LocalDateTime? = null,
        createdAt: LocalDateTime = DbClock.nowLocalDateTime(),
    ): Uuid {
        val roomId = Uuid.random()
        transaction {
            ConferenceRoomTable.insert {
                it[ConferenceRoomTable.id] = roomId
                it[title] = "Sitzung"
                it[description] = ""
                it[livekitRoomName] = "lc-$roomId"
                it[createdByMemberId] = openedBy
                it[ConferenceRoomTable.createdAt] = createdAt
                it[ConferenceRoomTable.endedAt] = endedAt
                it[maxParticipants] = 25
                it[allowFederationGuests] = false
                it[encounterSpaceId] = spaceId
            }
        }
        return roomId
    }

    fun insertParticipation(
        roomId: Uuid,
        memberId: Uuid,
        joinedAt: LocalDateTime = DbClock.nowLocalDateTime(),
    ) {
        transaction {
            ConferenceParticipationTable.insert {
                it[ConferenceParticipationTable.id] = Uuid.random()
                it[ConferenceParticipationTable.roomId] = roomId
                it[ConferenceParticipationTable.memberId] = memberId
                it[role] = network.lapis.cloud.shared.domain.ConferenceRole.PARTICIPANT
                it[ConferenceParticipationTable.joinedAt] = joinedAt
                it[leftAt] = null
            }
        }
    }

    /** Removes one presence row directly (what the poller or a crashed client leaves behind). */
    fun deleteParticipation(
        roomId: Uuid,
        memberId: Uuid,
    ) {
        transaction {
            ConferenceParticipationTable.deleteWhere {
                (ConferenceParticipationTable.roomId eq roomId) and (ConferenceParticipationTable.memberId eq memberId)
            }
        }
    }

    fun participationCount(roomId: Uuid): Long =
        transaction { ConferenceParticipationTable.selectAll().where { ConferenceParticipationTable.roomId eq roomId }.count() }

    fun auditCount(): Long = transaction { AuditLogEntryTable.selectAll().count() }

    fun cleanUp() {
        if (memberIds.isEmpty() && spaceIds.isEmpty()) return
        transaction {
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) { it[actorMemberId] = null }
            val roomIds =
                ConferenceRoomTable
                    .selectAll()
                    .where {
                        (ConferenceRoomTable.encounterSpaceId inList spaceIds) or
                            (ConferenceRoomTable.createdByMemberId inList memberIds)
                    }.map { it[ConferenceRoomTable.id] }
            ConferenceGuestConsentAcknowledgmentTable.deleteWhere {
                (ConferenceGuestConsentAcknowledgmentTable.roomId inList roomIds) or
                    (ConferenceGuestConsentAcknowledgmentTable.memberId inList memberIds)
            }
            ConferenceParticipationTable.deleteWhere {
                (ConferenceParticipationTable.roomId inList roomIds) or (ConferenceParticipationTable.memberId inList memberIds)
            }
            ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id inList roomIds }
            EncounterSpaceRoleTable.deleteWhere {
                (EncounterSpaceRoleTable.spaceId inList spaceIds) or (EncounterSpaceRoleTable.memberId inList memberIds)
            }
            EncounterConsentAcknowledgmentTable.deleteWhere { EncounterConsentAcknowledgmentTable.memberId inList memberIds }
            EncounterSpaceTable.deleteWhere {
                (EncounterSpaceTable.id inList spaceIds) or
                    (EncounterSpaceTable.createdByMemberId inList memberIds)
            }
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberStatusHistoryTable.deleteWhere { MemberStatusHistoryTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
    }
}

/** The `video` grant of a LiveKit join token (the signature is verified by `LiveKitAccessTokenTest`, not here). */
internal fun videoGrantOf(jwt: String): Map<String, Any> =
    com.nimbusds.jwt.SignedJWT
        .parse(jwt)
        .jwtClaimsSet
        .getJSONObjectClaim("video")

/** The token's lifetime in seconds (`exp - nbf`). */
internal fun tokenLifetimeSeconds(jwt: String): Long {
    val claims =
        com.nimbusds.jwt.SignedJWT
            .parse(jwt)
            .jwtClaimsSet
    return (claims.expirationTime.time - claims.notBeforeTime.time) / 1000
}

/** The audit entries of an encounter space, oldest first, as (action, after-snapshot, actor). */
internal fun auditEntriesOf(spaceId: Uuid): List<Triple<String, String?, Uuid?>> =
    transaction {
        AuditLogEntryTable
            .selectAll()
            .where {
                (AuditLogEntryTable.entityId eq spaceId) and
                    (AuditLogEntryTable.entityType eq network.lapis.cloud.shared.domain.AuditEntityType.ENCOUNTER_SPACE)
            }.orderBy(AuditLogEntryTable.sequenceNumber)
            .map { Triple(it[AuditLogEntryTable.action].name, it[AuditLogEntryTable.afterSnapshot], it[AuditLogEntryTable.actorMemberId]) }
    }
