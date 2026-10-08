package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.LiveKitAccessToken
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.conference.TurnCredentialMinter
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EncounterConsentAcknowledgmentTable
import network.lapis.cloud.server.db.generated.EncounterSpaceRoleTable
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.isUniqueViolation
import network.lapis.cloud.server.db.withSavepoint
import network.lapis.cloud.server.encounter.EncounterConsentText
import network.lapis.cloud.server.encounter.EncounterEntryNotifier
import network.lapis.cloud.server.encounter.EncounterModerationState
import network.lapis.cloud.server.encounter.EncounterRoles
import network.lapis.cloud.server.encounter.EncounterSeatState
import network.lapis.cloud.server.encounter.EncounterSessionTeardown
import network.lapis.cloud.server.encounter.EncounterSessions
import network.lapis.cloud.server.encounter.EncounterSpaceViews
import network.lapis.cloud.server.encounter.effectiveMaxParticipants
import network.lapis.cloud.server.encounter.encounterConsentFor
import network.lapis.cloud.server.encounter.notifyModeOf
import network.lapis.cloud.server.encounter.parseReactionSet
import network.lapis.cloud.server.encounter.profileOf
import network.lapis.cloud.server.encounter.reactionSetCsv
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConferenceJoinTokenDto
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.ConferenceTurnServer
import network.lapis.cloud.shared.domain.ENCOUNTER_SEAT_MAX
import network.lapis.cloud.shared.domain.EncounterConsentDisclaimerDto
import network.lapis.cloud.shared.domain.EncounterConsentInput
import network.lapis.cloud.shared.domain.EncounterEntryDto
import network.lapis.cloud.shared.domain.EncounterEntryInfoDto
import network.lapis.cloud.shared.domain.EncounterGuestPolicy
import network.lapis.cloud.shared.domain.EncounterNotifyMode
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterReactionOption
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.domain.EncounterSpaceInput
import network.lapis.cloud.shared.domain.EncounterSpaceMode
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.EncounterSpaceRoleAssignmentInput
import network.lapis.cloud.shared.domain.EncounterSpaceRoleDto
import network.lapis.cloud.shared.domain.EncounterTheme
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.encounterSeatCapacity
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.ServiceBusyException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

// Privacy rule of this file (Art. 9 GDPR): never log a member id, identity or display name together with a space or room id above DEBUG.
private val logger = KotlinLogging.logger {}

/** LiveKit `empty_timeout` of an encounter room: a pulpit that is briefly empty (a break) must not tear the session down. */
internal const val ENCOUNTER_EMPTY_TIMEOUT_SECONDS = 1800

/**
 * LiveKit `departure_timeout` of an encounter room. `empty_timeout` only covers a room nobody has joined yet; once
 * someone has joined and the last participant leaves, LiveKit closes the room after `departure_timeout` (default 20 s).
 * Without this, a pulpit that tests audio and disconnects would lose the room before the congregation arrives.
 */
internal const val ENCOUNTER_DEPARTURE_TIMEOUT_SECONDS = 1800

/** Slots of a session that are kept free for office holders, so a full congregation can never lock the pulpit out. */
internal const val ENCOUNTER_OFFICER_RESERVED_SLOTS = 2

private const val MAX_TITLE_LENGTH = 200
private const val MAX_DESCRIPTION_LENGTH = 1000
private const val MAX_NOTICE_LENGTH = 200
private const val MAX_ROLE_ASSIGNMENTS = 20
private const val MAX_REACTION_OPTIONS = 8
private const val MAX_LIST_RESULTS = 200
private const val MIN_SPACE_PARTICIPANTS = 2

/** BOARD/ADMIN, the two roles [network.lapis.cloud.server.security.isPrivileged] treats as privileged. */
private val PRIVILEGED_ACCOUNT_ROLES = listOf(AccountRole.BOARD, AccountRole.ADMIN)

/** The conflict thrown by every call that needs an open session while the space is closed. */
private const val SPACE_CLOSED_MESSAGE = "The encounter space is not open"

/**
 * Welle V1.9.61 "Begegnungsraum" (B1) -- see [IEncounterSpaceService] KDoc for the access matrix and
 * `docs/architecture/encounter-space.adoc` for the architecture and the Art. 9 GDPR decisions. Summary of the rules this class enforces:
 *
 * - **Lock order** (every writer, no exception): (1) `encounter_space FOR UPDATE`, (2) `conference_room FOR UPDATE`, (3) the caller's
 *   status is READ without a lock, (4) inserts/deletes, (5) [AuditLogRecorder.record] LAST. Never `FOR UPDATE` on `member`.
 * - **External calls never inside `transaction {}`** (LiveKit admin calls, token minting): the usual prepare / act / re-verify shape of
 *   [ConferenceService.joinRoom], with the space AND room rows re-locked and the authorization re-derived in the final transaction.
 * - **No lasting attendance trace**: the participation row exists only while the person is present (deleted on leave, on close, by the
 *   poller). The audit log only receives configuration changes and open/close with the office holder as actor -- never entering,
 *   leaving, presence, removing or silencing.
 * - **Listen-only congregation**: only an ACTIVE PULPIT/STEWARD office holder receives a token with `canPublish = true`.
 *
 * - **Entry notice (V1.9.76)**: when a person WITHOUT an office is newly admitted, [entryNotifier] may send the office holders an
 *   anonymous e-mail -- called only AFTER the entry transaction committed, never throwing, never writing a trace.
 *
 * Constructed per RPC request (see `Application.module`'s `registerService`), therefore EVERY throttle, [moderationState] and
 * [entryNotifier] are constructor parameters WITHOUT a default and come from module-scoped singletons -- a default would silently give
 * every request a fresh, empty limiter or notice state (the lesson of `conferenceMeetingBindRateLimiter`).
 */
class EncounterSpaceService(
    private val call: ApplicationCall,
    private val liveKitAdminClient: LiveKitAdminClient,
    private val moderationState: EncounterModerationState,
    private val seatState: EncounterSeatState,
    private val entryNotifier: EncounterEntryNotifier,
    private val listRateLimiter: FederationInboxRateLimiter,
    private val enterRateLimiter: FederationInboxRateLimiter,
    private val leaveRateLimiter: FederationInboxRateLimiter,
    private val openCloseRateLimiter: FederationInboxRateLimiter,
    private val moderationRateLimiter: FederationInboxRateLimiter,
    private val configRateLimiter: FederationInboxRateLimiter,
    private val seatRateLimiter: FederationInboxRateLimiter,
    private val config: ConferenceConfig = ConferenceConfig.load(),
) : IEncounterSpaceService {
    // ── Reads ─────────────────────────────────────────────────────────────

    override suspend fun listSpaces(): List<EncounterSpaceDto> {
        val current = resolveCurrentMember(call)
        requireWithinRate(limiter = listRateLimiter, memberId = current.memberId)
        return transaction {
            val status = requireConferenceEligibleMembership(memberId = current.memberId)
            val rows =
                EncounterSpaceTable
                    .selectAll()
                    .orderBy(EncounterSpaceTable.createdAt, SortOrder.DESC)
                    .limit(MAX_LIST_RESULTS)
                    .filter { isListable(row = it, current = current, status = status) }
            EncounterSpaceViews.toDtos(rows = rows, current = current, config = config)
        }
    }

    override suspend fun getSpace(spaceId: String): EncounterSpaceDto {
        val current = resolveCurrentMember(call)
        requireWithinRate(limiter = listRateLimiter, memberId = current.memberId)
        val id = spaceId.toSpaceUuid()
        return transaction {
            val status = requireConferenceEligibleMembership(memberId = current.memberId)
            val row = loadVisibleSpace(spaceId = id, current = current, status = status)
            EncounterSpaceViews.toDtos(rows = listOf(row), current = current, config = config).single()
        }
    }

    override suspend fun getEntryInfo(spaceId: String): EncounterEntryInfoDto {
        val current = resolveCurrentMember(call)
        requireWithinRate(limiter = listRateLimiter, memberId = current.memberId)
        val id = spaceId.toSpaceUuid()
        return transaction {
            val status = requireConferenceEligibleMembership(memberId = current.memberId)
            val row = loadVisibleSpace(spaceId = id, current = current, status = status)
            val consentText = encounterConsentFor(profileOf(row))
            val consentRequired =
                status in MemberStatusSets.NON_MEMBER && !hasCurrentConsent(memberId = current.memberId, text = consentText)
            EncounterEntryInfoDto(
                space = EncounterSpaceViews.toDtos(rows = listOf(row), current = current, config = config).single(),
                consentRequired = consentRequired,
                disclaimer = if (consentRequired) disclaimerDto(consentText) else null,
            )
        }
    }

    override suspend fun listSpaceRoles(spaceId: String): List<EncounterSpaceRoleDto> {
        val current = resolveCurrentMember(call)
        requireWithinRate(limiter = configRateLimiter, memberId = current.memberId)
        val id = spaceId.toSpaceUuid()
        return transaction {
            requireConfigAuthority(current)
            loadSpace(spaceId = id)
            (EncounterSpaceRoleTable innerJoin MemberTable)
                .selectAll()
                .where { EncounterSpaceRoleTable.spaceId eq id }
                .map {
                    EncounterSpaceRoleDto(
                        memberId = it[EncounterSpaceRoleTable.memberId].toString(),
                        displayName = it[MemberTable.displayName],
                        role = EncounterSpaceRole.valueOf(it[EncounterSpaceRoleTable.role]),
                    )
                }.sortedWith(compareBy({ it.role }, { it.displayName }))
        }
    }

    // ── Configuration (BOARD/ADMIN) ───────────────────────────────────────

    override suspend fun createSpace(input: EncounterSpaceInput): EncounterSpaceDto {
        val current = resolveCurrentMember(call)
        requireWithinRate(limiter = configRateLimiter, memberId = current.memberId)
        val valid = validateInput(input)
        val now = nowLocalDateTime()
        return transaction {
            requireConfigAuthority(current)
            val newId = Uuid.random()
            val newProfile = valid.profile ?: EncounterProfile.CHURCH_SERVICE
            val newReactions = valid.reactions ?: EncounterReactionOption.defaultsFor(newProfile)
            EncounterSpaceTable.insert {
                it[EncounterSpaceTable.id] = newId
                it[EncounterSpaceTable.title] = valid.title
                it[EncounterSpaceTable.description] = input.description
                // theme_key stays frozen at CHURCH (V1.9.67): old cached clients still decode EncounterTheme; the profile is the truth.
                it[EncounterSpaceTable.themeKey] = EncounterTheme.CHURCH.name
                it[EncounterSpaceTable.profile] = newProfile.name
                it[EncounterSpaceTable.reactionSet] = reactionSetCsv(newReactions)
                it[EncounterSpaceTable.notifyMode] = (valid.notifyMode ?: EncounterNotifyMode.NONE).name
                it[EncounterSpaceTable.mode] = EncounterSpaceMode.SERVICE.name
                it[EncounterSpaceTable.guestPolicy] = input.guestPolicy.name
                it[EncounterSpaceTable.maxParticipants] = input.maxParticipants
                it[EncounterSpaceTable.closedNotice] = valid.closedNotice
                it[EncounterSpaceTable.createdAt] = now
                it[EncounterSpaceTable.createdByMemberId] = current.memberId
                it[EncounterSpaceTable.updatedAt] = null
                it[EncounterSpaceTable.archivedAt] = null
            }
            val row = loadSpace(spaceId = newId)
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.ENCOUNTER_SPACE,
                entityId = newId,
                action = AuditAction.CREATE,
                after = EncounterSpaceViews.configSnapshot(row),
            )
            EncounterSpaceViews.toDtos(rows = listOf(row), current = current, config = config).single()
        }
    }

    override suspend fun updateSpace(
        spaceId: String,
        input: EncounterSpaceInput,
    ): EncounterSpaceDto {
        val current = resolveCurrentMember(call)
        requireWithinRate(limiter = configRateLimiter, memberId = current.memberId)
        val id = spaceId.toSpaceUuid()
        val valid = validateInput(input)
        val now = nowLocalDateTime()
        var notifyModeChanged = false
        val updated =
            transaction {
                requireConfigAuthority(current)
                val before = lockSpace(spaceId = id)
                if (before[EncounterSpaceTable.archivedAt] != null) throw ConflictException("An archived encounter space cannot be changed")
                if (input.guestPolicy.name != before[EncounterSpaceTable.guestPolicy] &&
                    EncounterSessions.openSession(spaceId = id) != null
                ) {
                    // Already-present non-members would otherwise stay inside a room that no longer admits them.
                    throw ConflictException("The guest policy can only be changed while the encounter space is closed")
                }
                val newProfile = valid.profile ?: profileOf(before)
                val newReactions = valid.reactions ?: parseReactionSet(before[EncounterSpaceTable.reactionSet])
                val changesProfile =
                    newProfile != profileOf(before) ||
                        reactionSetCsv(newReactions) != reactionSetCsv(parseReactionSet(before[EncounterSpaceTable.reactionSet]))
                if (changesProfile && EncounterSessions.openSession(spaceId = id) != null) {
                    // The vocabulary and the reaction set cannot change under people who are present (same pattern as the guest policy).
                    throw ConflictException("The profile and the reactions can only be changed while the encounter space is closed")
                }
                // The notify mode may change while the room is open (it only affects who is told about FUTURE entries).
                val oldNotifyMode = notifyModeOf(before)
                val newNotifyMode = valid.notifyMode ?: oldNotifyMode
                notifyModeChanged = newNotifyMode != oldNotifyMode
                EncounterSpaceTable.update({ EncounterSpaceTable.id eq id }) {
                    it[EncounterSpaceTable.title] = valid.title
                    it[EncounterSpaceTable.description] = input.description
                    it[EncounterSpaceTable.profile] = newProfile.name
                    it[EncounterSpaceTable.reactionSet] = reactionSetCsv(newReactions)
                    it[EncounterSpaceTable.notifyMode] = newNotifyMode.name
                    it[EncounterSpaceTable.guestPolicy] = input.guestPolicy.name
                    it[EncounterSpaceTable.maxParticipants] = input.maxParticipants
                    it[EncounterSpaceTable.closedNotice] = valid.closedNotice
                    it[EncounterSpaceTable.updatedAt] = now
                }
                val after = loadSpace(spaceId = id)
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.ENCOUNTER_SPACE,
                    entityId = id,
                    action = AuditAction.UPDATE,
                    before = EncounterSpaceViews.configSnapshot(before),
                    after = EncounterSpaceViews.configSnapshot(after),
                )
                EncounterSpaceViews.toDtos(rows = listOf(after), current = current, config = config).single()
            }
        // After the commit: on a rolled-back update the notice state stays untouched.
        if (notifyModeChanged) entryNotifier.clearSpace(id)
        return updated
    }

    override suspend fun archiveSpace(spaceId: String): EncounterSpaceDto {
        val current = resolveCurrentMember(call)
        requireWithinRate(limiter = configRateLimiter, memberId = current.memberId)
        val id = spaceId.toSpaceUuid()
        val now = nowLocalDateTime()
        return transaction {
            requireConfigAuthority(current)
            val before = lockSpace(spaceId = id)
            if (before[EncounterSpaceTable.archivedAt] == null) {
                if (EncounterSessions.openSession(spaceId = id) != null) {
                    throw ConflictException("An open encounter space cannot be archived -- close it first")
                }
                EncounterSpaceTable.update({ EncounterSpaceTable.id eq id }) {
                    it[EncounterSpaceTable.archivedAt] = now
                    it[EncounterSpaceTable.updatedAt] = now
                }
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.ENCOUNTER_SPACE,
                    entityId = id,
                    action = AuditAction.UPDATE,
                    before = EncounterSpaceViews.configSnapshot(before),
                    after = EncounterSpaceViews.configSnapshot(loadSpace(spaceId = id)),
                )
            }
            EncounterSpaceViews.toDtos(rows = listOf(loadSpace(spaceId = id)), current = current, config = config).single()
        }
    }

    override suspend fun setSpaceRoles(
        spaceId: String,
        assignments: List<EncounterSpaceRoleAssignmentInput>,
    ): List<EncounterSpaceRoleDto> {
        val current = resolveCurrentMember(call)
        requireWithinRate(limiter = configRateLimiter, memberId = current.memberId)
        val id = spaceId.toSpaceUuid()
        if (assignments.size > MAX_ROLE_ASSIGNMENTS) throw BadRequestException("at most $MAX_ROLE_ASSIGNMENTS role assignments are allowed")
        val parsed = assignments.map { it.memberId.toBodyUuid() to it.role }
        if (parsed.map { it.first }.distinct().size != parsed.size) throw BadRequestException("a member may hold only one role per space")
        val now = nowLocalDateTime()
        // Filled inside the transaction: who lost an office while a session is running (disconnected AFTER the commit, outside any transaction).
        var withdrawn: List<Uuid> = emptyList()
        var openRoomName: String? = null
        val result =
            transaction {
                requireConfigAuthority(current)
                val space = lockSpace(spaceId = id)
                if (space[EncounterSpaceTable.archivedAt] != null) throw ConflictException("An archived encounter space cannot be changed")
                val statuses =
                    if (parsed.isEmpty()) {
                        emptyMap()
                    } else {
                        MemberTable
                            .selectAll()
                            .where { MemberTable.id inList parsed.map { it.first } }
                            .associate { it[MemberTable.id] to it[MemberTable.status] }
                    }
                parsed.forEach { (memberId, _) ->
                    if (statuses[memberId] !=
                        MemberStatus.ACTIVE
                    ) {
                        throw ConflictException("An office can only be given to an active member")
                    }
                }
                val beforeRoles = currentRoleRows(spaceId = id)
                withdrawn = beforeRoles.map { it.first }.filter { old -> parsed.none { it.first == old } }
                openRoomName = EncounterSessions.openSession(spaceId = id)?.get(ConferenceRoomTable.livekitRoomName)
                EncounterSpaceRoleTable.deleteWhere { EncounterSpaceRoleTable.spaceId eq id }
                parsed.forEach { (memberId, role) ->
                    EncounterSpaceRoleTable.insert {
                        it[EncounterSpaceRoleTable.spaceId] = id
                        it[EncounterSpaceRoleTable.memberId] = memberId
                        it[EncounterSpaceRoleTable.role] = role.name
                    }
                }
                EncounterSpaceTable.update({ EncounterSpaceTable.id eq id }) { it[EncounterSpaceTable.updatedAt] = now }
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.ENCOUNTER_SPACE,
                    entityId = id,
                    action = AuditAction.UPDATE,
                    before = EncounterSpaceViews.rolesSnapshot(beforeRoles),
                    after = EncounterSpaceViews.rolesSnapshot(parsed.map { it.first to it.second.name }),
                )
                (EncounterSpaceRoleTable innerJoin MemberTable)
                    .selectAll()
                    .where { EncounterSpaceRoleTable.spaceId eq id }
                    .map {
                        EncounterSpaceRoleDto(
                            memberId = it[EncounterSpaceRoleTable.memberId].toString(),
                            displayName = it[MemberTable.displayName],
                            role = EncounterSpaceRole.valueOf(it[EncounterSpaceRoleTable.role]),
                        )
                    }.sortedWith(compareBy({ it.role }, { it.displayName }))
            }
        disconnectWithdrawnOfficeHolders(roomName = openRoomName, memberIds = withdrawn)
        return result
    }

    /**
     * An office holder keeps a token with `canPublish = true` until it expires; so whoever just lost the office is disconnected from the
     * running session (best effort, outside any transaction -- the roles are already committed). A re-entry gets a listen-only token.
     */
    private suspend fun disconnectWithdrawnOfficeHolders(
        roomName: String?,
        memberIds: List<Uuid>,
    ) {
        if (roomName == null || memberIds.isEmpty()) return
        try {
            val live = liveKitAdminClient.listParticipants(roomName).map { it.identity }.toSet()
            memberIds.filter { it.toString() in live }.forEach {
                liveKitAdminClient.removeParticipant(
                    room = roomName,
                    identity = it.toString(),
                )
            }
        } catch (e: LiveKitAdminException) {
            logger.warn { "could not disconnect a withdrawn office holder -- the token expires by itself" }
        }
    }

    // ── Session lifecycle (office holders) ────────────────────────────────

    override suspend fun openSpace(spaceId: String): EncounterSpaceDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = openCloseRateLimiter, memberId = current.memberId)
        val id = spaceId.toSpaceUuid()

        // Tx1: authorize; an already open session is returned unchanged (idempotent).
        val alreadyOpen =
            transaction {
                requireConferenceEligibleMembership(memberId = current.memberId)
                val space = lockSpace(spaceId = id)
                requireSpaceModerator(spaceId = id, current = current)
                if (space[EncounterSpaceTable.archivedAt] != null) throw ConflictException("An archived encounter space cannot be opened")
                if (EncounterSessions.openSession(spaceId = id) != null) {
                    EncounterSpaceViews.toDtos(rows = listOf(space), current = current, config = config).single()
                } else {
                    null
                }
            }
        if (alreadyOpen != null) return alreadyOpen

        // Outside any transaction: the LiveKit room. Named "newRoomName" (see ConferenceService.createRoom for the shadowing footgun).
        val newRoomName = "lc-${Uuid.random()}"
        val maxForSession = transaction { effectiveMaxParticipants(spaceRow = loadSpace(spaceId = id), config = config) }
        liveKitCall {
            liveKitAdminClient.createRoom(
                name = newRoomName,
                maxParticipants = maxForSession,
                emptyTimeoutSeconds = ENCOUNTER_EMPTY_TIMEOUT_SECONDS,
                departureTimeoutSeconds = ENCOUNTER_DEPARTURE_TIMEOUT_SECONDS,
            )
        }
        val now = nowLocalDateTime()
        val outcome =
            try {
                transaction {
                    // Re-verify under the locks: the space may have been archived, the office withdrawn or a concurrent open may have won.
                    requireConferenceEligibleMembership(memberId = current.memberId)
                    val space = lockSpace(spaceId = id)
                    requireSpaceModerator(spaceId = id, current = current)
                    if (space[EncounterSpaceTable.archivedAt] !=
                        null
                    ) {
                        throw ConflictException("An archived encounter space cannot be opened")
                    }
                    if (EncounterSessions.openSession(spaceId = id, forUpdate = true) != null) {
                        OpenOutcome(dto = null, lostRace = true)
                    } else {
                        ConferenceRoomTable.insert {
                            it[ConferenceRoomTable.id] = Uuid.random()
                            it[ConferenceRoomTable.title] = space[EncounterSpaceTable.title]
                            it[ConferenceRoomTable.description] = space[EncounterSpaceTable.description]
                            it[ConferenceRoomTable.livekitRoomName] = newRoomName
                            it[ConferenceRoomTable.createdByMemberId] = current.memberId
                            it[ConferenceRoomTable.createdAt] = now
                            it[ConferenceRoomTable.endedAt] = null
                            it[ConferenceRoomTable.maxParticipants] = maxForSession
                            it[ConferenceRoomTable.allowFederationGuests] =
                                space[EncounterSpaceTable.guestPolicy] == EncounterGuestPolicy.MEMBERS_AND_GUESTS.name
                            it[ConferenceRoomTable.encounterSpaceId] = id
                        }
                        AuditLogRecorder.record(
                            actorMemberId = current.memberId,
                            actorRole = current.role,
                            entityType = AuditEntityType.ENCOUNTER_SPACE,
                            entityId = id,
                            action = AuditAction.UPDATE,
                            after = STATE_OPEN_SNAPSHOT,
                        )
                        OpenOutcome(
                            dto = EncounterSpaceViews.toDtos(rows = listOf(space), current = current, config = config).single(),
                            lostRace = false,
                        )
                    }
                }
            } catch (e: Throwable) {
                deleteRoomBestEffort(newRoomName)
                throw e
            }
        if (outcome.lostRace) {
            deleteRoomBestEffort(newRoomName)
            return getSpace(spaceId)
        }
        return outcome.dto!!
    }

    override suspend fun closeSpace(spaceId: String): EncounterSpaceDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = openCloseRateLimiter, memberId = current.memberId)
        val id = spaceId.toSpaceUuid()
        val now = nowLocalDateTime()

        val session =
            transaction {
                requireConferenceEligibleMembership(memberId = current.memberId)
                lockSpace(spaceId = id)
                requireSpaceModerator(spaceId = id, current = current)
                EncounterSessions.openSession(spaceId = id)
            }
        if (session != null) {
            val roomId = session[ConferenceRoomTable.id]
            // Data protection beats LiveKit availability: a failed deleteRoom is only logged; the database is cleaned up regardless and
            // the poller retries the LiveKit deletion (see EncounterSpacePoller).
            try {
                liveKitAdminClient.deleteRoom(session[ConferenceRoomTable.livekitRoomName])
            } catch (e: LiveKitAdminException) {
                logger.warn { "closing an encounter session: the LiveKit room could not be deleted -- the poller retries" }
            }
            transaction {
                lockSpace(spaceId = id)
                val room =
                    ConferenceRoomTable
                        .selectAll()
                        .where { ConferenceRoomTable.id eq roomId }
                        .forUpdate()
                        .singleOrNull()
                if (room != null && room[ConferenceRoomTable.endedAt] == null) {
                    EncounterSessionTeardown.endSessionInTx(roomId = roomId, now = now)
                    AuditLogRecorder.record(
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        entityType = AuditEntityType.ENCOUNTER_SPACE,
                        entityId = id,
                        action = AuditAction.UPDATE,
                        after = STATE_CLOSED_MANUAL_SNAPSHOT,
                    )
                }
            }
            moderationState.clear(roomId)
            seatState.clear(roomId)
            entryNotifier.clearSession(roomId)
        }
        return transaction { dtoOf(spaceId = id, current = current) }
    }

    // ── Presence (everybody) ──────────────────────────────────────────────

    override suspend fun enterSpace(
        spaceId: String,
        consent: EncounterConsentInput?,
    ): EncounterEntryDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = enterRateLimiter, memberId = current.memberId)
        val id = spaceId.toSpaceUuid()

        // Tx1 (no lock): authorize and gather what the token needs.
        val prep = transaction { prepareEntry(spaceId = id, current = current, consent = consent) }

        // Outside any transaction: sign the token and mint the TURN credential.
        val effectiveTtl = if (prep.isNonMember) config.guestTokenTtlMinutes else config.tokenTtlMinutes
        val minted =
            LiveKitAccessToken.mintParticipantToken(
                apiKey = config.apiKey,
                apiSecret = config.apiSecret,
                roomName = prep.livekitRoomName,
                identity = current.memberId.toString(),
                displayName = prep.displayName,
                ttl = effectiveTtl.minutes,
                canPublish = prep.canPublish,
                canPublishData = prep.canPublishData,
            )
        val turnServers = mintTurnServers(memberId = current.memberId, ttlMinutes = effectiveTtl)
        val now = nowLocalDateTime()

        // Tx2: lock, re-verify everything, enforce the ceilings, write the (transient) presence row.
        val (admittedNew, notifyMode) =
            transaction {
                val spaceRow = lockSpace(spaceId = id)
                val room =
                    ConferenceRoomTable
                        .selectAll()
                        .where { ConferenceRoomTable.id eq prep.roomId }
                        .forUpdate()
                        .singleOrNull()
                if (room == null || room[ConferenceRoomTable.endedAt] != null) throw ConflictException(SPACE_CLOSED_MESSAGE)
                val fresh = prepareEntry(spaceId = id, current = current, consent = consent)
                if (fresh.roomId != prep.roomId || fresh.canPublish != prep.canPublish || fresh.canPublishData != prep.canPublishData) {
                    // The session, the office or the silence changed while the token was being minted: the token must not be returned.
                    throw ConflictException("The access rights changed -- please try again")
                }
                admitInTx(prep = fresh, current = current, consent = consent, now = now) to notifyModeOf(spaceRow)
            }
        // V1.9.79: every entry starts without a seat (a reload / re-entry must not resurrect a seat of an earlier connection).
        seatState.release(sessionRoomId = prep.roomId, memberId = current.memberId)
        // After the commit (Exposed may re-run the block on an SQLException, so the hook must not sit inside it). Only a NEW presence row
        // of a person without an office counts: a reconnect reuses its row, BOARD/ADMIN without an office are not "guests".
        if (admittedNew && prep.presenceRole == EncounterPresenceRole.CONGREGATION && !current.isPrivileged) {
            try {
                entryNotifier.onGuestEntered(spaceId = id, sessionRoomId = prep.roomId, mode = notifyMode)
            } catch (e: Exception) {
                logger.warn { "encounter entry notice failed (${e::class.simpleName})" }
            }
        }
        return EncounterEntryDto(
            join =
                ConferenceJoinTokenDto(
                    roomId = prep.roomId.toString(),
                    livekitRoomName = prep.livekitRoomName,
                    serverUrl = config.livekitUrl,
                    token = minted.jwt,
                    identity = current.memberId.toString(),
                    displayName = prep.displayName,
                    role = if (prep.canModerate) ConferenceRole.MODERATOR else ConferenceRole.PARTICIPANT,
                    expiresAt = minted.expiresAt.toLocalDateTime(ServerClock.zone),
                    turnServers = turnServers,
                ),
            presenceRole = prep.presenceRole,
            canPublish = prep.canPublish,
            canPublishData = prep.canPublishData,
        )
    }

    override suspend fun leaveSpace(spaceId: String) {
        val current = resolveCurrentMember(call)
        requireWithinRate(limiter = leaveRateLimiter, memberId = current.memberId)
        val id = spaceId.toSpaceUuid()
        val sessionRooms =
            transaction {
                // Deletes the caller's presence rows of EVERY session of this space (idempotent, also clears an orphan of an ended
                // session) -- never an UPDATE of left_at, which would keep a trace.
                val rooms =
                    ConferenceRoomTable
                        .selectAll()
                        .where { ConferenceRoomTable.encounterSpaceId eq id }
                        .map { it[ConferenceRoomTable.id] }
                if (rooms.isNotEmpty()) {
                    ConferenceParticipationTable.deleteWhere {
                        (ConferenceParticipationTable.roomId inList rooms) and
                            (ConferenceParticipationTable.memberId eq current.memberId)
                    }
                }
                rooms
            }
        // V1.9.79: the seat goes with the person (in memory only, after the commit).
        sessionRooms.forEach { seatState.release(sessionRoomId = it, memberId = current.memberId) }
    }

    override suspend fun listPresent(spaceId: String): List<EncounterPresentDto> {
        val current = resolveCurrentMember(call)
        requireWithinRate(limiter = listRateLimiter, memberId = current.memberId)
        val id = spaceId.toSpaceUuid()
        return transaction {
            val status = requireConferenceEligibleMembership(memberId = current.memberId)
            loadVisibleSpace(spaceId = id, current = current, status = status)
            val session = EncounterSessions.openSession(spaceId = id) ?: throw ForbiddenException()
            val roomId = session[ConferenceRoomTable.id]
            val view = presentViewInTx(roomId = roomId, spaceId = id, currentId = current.memberId)
            presentDtos(roomId = roomId, view = view)
        }
    }

    override suspend fun selectSeat(
        spaceId: String,
        seat: Int?,
    ): List<EncounterPresentDto> {
        val current = resolveCurrentMember(call)
        // Throttle FIRST and with its own exception type: Kilua transmits only the type, and "seat taken" (ConflictException) must stay
        // distinguishable from "slow down" and "seat table full" (both ServiceBusyException).
        requireSeatRate(limiter = seatRateLimiter, memberId = current.memberId)
        if (seat != null && seat !in 0 until ENCOUNTER_SEAT_MAX) throw BadRequestException("Seat out of range")
        val id = spaceId.toSpaceUuid()
        return transaction {
            val status = requireConferenceEligibleMembership(memberId = current.memberId)
            loadVisibleSpace(spaceId = id, current = current, status = status)
            val session = EncounterSessions.openSession(spaceId = id) ?: throw ForbiddenException()
            val roomId = session[ConferenceRoomTable.id]
            val view = presentViewInTx(roomId = roomId, spaceId = id, currentId = current.memberId)
            if (view.roleOf(current.memberId) != EncounterPresenceRole.CONGREGATION) throw ForbiddenException()
            if (seat != null && seat >= encounterSeatCapacity(view.congregation().size)) throw BadRequestException("Seat out of range")
            when (
                seatState.select(
                    sessionRoomId = roomId,
                    memberId = current.memberId,
                    seat = seat,
                    present = view.congregation(),
                )
            ) {
                EncounterSeatState.Outcome.Ok -> Unit
                EncounterSeatState.Outcome.Taken -> throw ConflictException("Seat taken")
                EncounterSeatState.Outcome.Full -> throw ServiceBusyException("Seat table full")
            }
            presentDtos(roomId = roomId, view = view)
        }
    }

    /** The presence rows of one open session plus the office roles of its space, read in the caller's transaction. */
    private inner class PresentView(
        val participants: List<ResultRow>,
        val roles: Map<Uuid, EncounterSpaceRole>,
    ) {
        fun roleOf(memberId: Uuid): EncounterPresenceRole = roles[memberId].toPresenceRole()

        /** Members present who hold no office (the only ones that can sit). */
        fun congregation(): Set<Uuid> =
            participants
                .map { it[ConferenceParticipationTable.memberId] }
                .filter { roles[it] == null }
                .toSet()
    }

    /** Shared by [listPresent] and [selectSeat]: loads the presence rows and roles; [ForbiddenException] unless [currentId] is present. */
    private fun presentViewInTx(
        roomId: Uuid,
        spaceId: Uuid,
        currentId: Uuid,
    ): PresentView {
        val participants =
            (ConferenceParticipationTable innerJoin MemberTable)
                .selectAll()
                .where { ConferenceParticipationTable.roomId eq roomId }
                .toList()
        // Only somebody who is present themselves may see who is present -- BOARD/ADMIN who are not in the room see nothing.
        if (participants.none { it[ConferenceParticipationTable.memberId] == currentId }) throw ForbiddenException()
        val roles =
            (EncounterSpaceRoleTable innerJoin MemberTable)
                .selectAll()
                .where { (EncounterSpaceRoleTable.spaceId eq spaceId) and (MemberTable.status eq MemberStatus.ACTIVE) }
                .associate { it[EncounterSpaceRoleTable.memberId] to EncounterSpaceRole.valueOf(it[EncounterSpaceRoleTable.role]) }
        return PresentView(participants = participants, roles = roles)
    }

    private fun presentDtos(
        roomId: Uuid,
        view: PresentView,
    ): List<EncounterPresentDto> {
        // snapshot() also prunes seats of people who are gone or hold an office by now.
        val seats = seatState.snapshot(sessionRoomId = roomId, present = view.congregation())
        return view.participants
            .map { row ->
                val memberId = row[ConferenceParticipationTable.memberId]
                EncounterPresentDto(
                    memberId = memberId.toString(),
                    displayName = row[MemberTable.displayName],
                    role = view.roleOf(memberId),
                    isGuest = row[MemberTable.status] in MemberStatusSets.NON_MEMBER,
                    seat = seats[memberId],
                )
                // Sorted by name: the order must not reveal who arrived when.
            }.sortedBy { it.displayName }
    }

    // ── Moderation (office holders, BOARD/ADMIN) ──────────────────────────

    override suspend fun removeFromSpace(
        spaceId: String,
        memberId: String,
    ) {
        moderate(spaceId = spaceId, memberId = memberId, silenceOnly = false)
    }

    override suspend fun silenceInSpace(
        spaceId: String,
        memberId: String,
    ) {
        moderate(spaceId = spaceId, memberId = memberId, silenceOnly = true)
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private data class ValidInput(
        val title: String,
        val closedNotice: String?,
        val profile: EncounterProfile?,
        val reactions: List<EncounterReactionOption>?,
        val notifyMode: EncounterNotifyMode?,
    )

    private data class OpenOutcome(
        val dto: EncounterSpaceDto?,
        val lostRace: Boolean,
    )

    /** What [prepareEntry] derives for one entry attempt. [roomId]/[livekitRoomName] identify the session the token is minted for. */
    private data class EntryPrep(
        val roomId: Uuid,
        val livekitRoomName: String,
        val displayName: String,
        val status: MemberStatus,
        val presenceRole: EncounterPresenceRole,
        val canPublish: Boolean,
        val canPublishData: Boolean,
        val canModerate: Boolean,
        val effectiveMax: Int,
        val needsConsentRow: Boolean,
        val consentText: EncounterConsentText,
    ) {
        val isNonMember: Boolean get() = status in MemberStatusSets.NON_MEMBER
    }

    private fun validateInput(input: EncounterSpaceInput): ValidInput {
        val title = input.title.trim()
        if (title.isBlank()) throw BadRequestException("title must not be blank")
        if (title.length > MAX_TITLE_LENGTH) throw BadRequestException("title must be at most $MAX_TITLE_LENGTH characters")
        if (input.description.length > MAX_DESCRIPTION_LENGTH) {
            throw BadRequestException("description must be at most $MAX_DESCRIPTION_LENGTH characters")
        }
        val notice = input.closedNotice?.trim()?.takeIf { it.isNotEmpty() }
        if (notice != null && notice.length > MAX_NOTICE_LENGTH) {
            throw BadRequestException("closedNotice must be at most $MAX_NOTICE_LENGTH characters")
        }
        val max = input.maxParticipants
        if (max != null &&
            max < MIN_SPACE_PARTICIPANTS
        ) {
            throw BadRequestException("maxParticipants must be at least $MIN_SPACE_PARTICIPANTS")
        }
        val reactions = input.reactions
        if (reactions != null && reactions.size > MAX_REACTION_OPTIONS) {
            throw BadRequestException("at most $MAX_REACTION_OPTIONS reactions are allowed")
        }
        return ValidInput(
            title = title,
            closedNotice = notice,
            profile = input.profile,
            reactions = reactions?.let { EncounterReactionOption.normalize(it) },
            notifyMode = input.notifyMode,
        )
    }

    /** Everything an entry needs, derived from the database NOW. Throws on any denial. Runs inside a transaction (no lock itself). */
    private fun prepareEntry(
        spaceId: Uuid,
        current: CurrentMember,
        consent: EncounterConsentInput?,
    ): EntryPrep {
        val status = requireConferenceEligibleMembership(memberId = current.memberId)
        val space = loadVisibleSpace(spaceId = spaceId, current = current, status = status)
        if (space[EncounterSpaceTable.archivedAt] != null) throw ConflictException(SPACE_CLOSED_MESSAGE)
        val session = EncounterSessions.openSession(spaceId = spaceId) ?: throw ConflictException(SPACE_CLOSED_MESSAGE)
        val roomId = session[ConferenceRoomTable.id]
        if (moderationState.isBlocked(sessionRoomId = roomId, memberId = current.memberId)) {
            throw ForbiddenException("You were removed from this session")
        }
        val role = EncounterRoles.roleOf(spaceId = spaceId, memberId = current.memberId)
        // A non-member needs the current Art. 9 consent: either already on file, or submitted now (verbatim, never altered).
        var needsConsentRow = false
        val consentText = encounterConsentFor(profileOf(space))
        if (status in MemberStatusSets.NON_MEMBER && !hasCurrentConsent(memberId = current.memberId, text = consentText)) {
            val submitted =
                consent
                    ?: throw ConflictException(
                        "A non-member must acknowledge the current consent text before entering -- call getEntryInfo and submit it unmodified",
                    )
            if (!consentText.matches(version = submitted.consentVersion, sha256 = submitted.consentSha256)) {
                throw ConflictException("consentVersion/consentSha256 do not match the current consent text -- call getEntryInfo again")
            }
            needsConsentRow = true
        }
        return EntryPrep(
            roomId = roomId,
            livekitRoomName = session[ConferenceRoomTable.livekitRoomName],
            displayName = MemberTable.selectAll().where { MemberTable.id eq current.memberId }.single()[MemberTable.displayName],
            status = status,
            presenceRole = role.toPresenceRole(),
            // ONLY an ACTIVE office holder may publish. BOARD/ADMIN without an office listen only (F2).
            canPublish = role != null,
            canPublishData = !moderationState.isSilenced(sessionRoomId = roomId, memberId = current.memberId),
            canModerate = role != null || current.isPrivileged, // PULPIT and STEWARD, consistent with requireSpaceModerator
            effectiveMax = effectiveMaxParticipants(spaceRow = space, config = config),
            needsConsentRow = needsConsentRow,
            consentText = consentText,
        )
    }

    /**
     * The final, locked part of [enterSpace]: ceilings, the presence row, the consent proof. The space and room rows are already locked.
     * Returns `true` iff a NEW presence row was inserted (a reconnect reuses its row and returns `false`).
     */
    private fun admitInTx(
        prep: EntryPrep,
        current: CurrentMember,
        consent: EncounterConsentInput?,
        now: LocalDateTime,
    ): Boolean {
        val isOfficer = prep.presenceRole != EncounterPresenceRole.CONGREGATION
        val others =
            ConferenceParticipationTable
                .selectAll()
                .where {
                    (ConferenceParticipationTable.roomId eq prep.roomId) and
                        (ConferenceParticipationTable.memberId neq current.memberId)
                }
        val othersCount = others.count().toInt()
        if (othersCount >= prep.effectiveMax) throw ConflictException("This encounter space is full")
        if (!isOfficer && !current.isPrivileged) {
            val congregationCeiling = maxOf(prep.effectiveMax - ENCOUNTER_OFFICER_RESERVED_SLOTS, 1)
            if (othersCount >= congregationCeiling) throw ConflictException("This encounter space is full")
        }
        if (prep.isNonMember) {
            val nonMembers =
                (ConferenceParticipationTable innerJoin MemberTable)
                    .selectAll()
                    .where {
                        (ConferenceParticipationTable.roomId eq prep.roomId) and
                            (ConferenceParticipationTable.memberId neq current.memberId) and
                            (MemberTable.status inList MemberStatusSets.NON_MEMBER)
                    }.count()
            if (nonMembers >= config.maxNonMemberParticipants) throw ConflictException("This encounter space has reached its guest limit")
        }
        // At most ONE row per (session, person): a reconnect reuses it.
        val existing =
            ConferenceParticipationTable
                .selectAll()
                .where {
                    (ConferenceParticipationTable.roomId eq prep.roomId) and
                        (ConferenceParticipationTable.memberId eq current.memberId)
                }.limit(1)
                .any()
        if (!existing) {
            ConferenceParticipationTable.insert {
                it[ConferenceParticipationTable.id] = Uuid.random()
                it[ConferenceParticipationTable.roomId] = prep.roomId
                it[ConferenceParticipationTable.memberId] = current.memberId
                it[ConferenceParticipationTable.role] = if (prep.canModerate) ConferenceRole.MODERATOR else ConferenceRole.PARTICIPANT
                it[ConferenceParticipationTable.joinedAt] = now
                it[ConferenceParticipationTable.leftAt] = null
            }
        }
        if (prep.needsConsentRow) recordConsent(memberId = current.memberId, consent = consent!!, text = prep.consentText)
        return !existing
    }

    /**
     * Stores the proof of the explicit consent. Only a unique violation (SQLSTATE 23505) means "already on file" -- inside a savepoint,
     * because on PostgreSQL a failed statement would otherwise poison the whole transaction. Never a room/space reference, never a time.
     */
    private fun recordConsent(
        memberId: Uuid,
        consent: EncounterConsentInput,
        text: EncounterConsentText,
    ) {
        try {
            withSavepoint(name = "encounter_consent") {
                EncounterConsentAcknowledgmentTable.insert {
                    it[EncounterConsentAcknowledgmentTable.memberId] = memberId
                    it[EncounterConsentAcknowledgmentTable.consentVersion] = text.version
                    // Canonical lowercase hash: matches() is case-insensitive, hasCurrentConsent() compares exactly; the value equals the submitted one.
                    it[EncounterConsentAcknowledgmentTable.consentSha256] = text.sha256
                    it[EncounterConsentAcknowledgmentTable.acknowledgedOn] = OrganizationTimeZone.today()
                }
            }
        } catch (e: Exception) {
            if (!e.isUniqueViolation()) throw e
        }
    }

    private suspend fun moderate(
        spaceId: String,
        memberId: String,
        silenceOnly: Boolean,
    ) {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = moderationRateLimiter, memberId = current.memberId)
        val id = spaceId.toSpaceUuid()
        val targetId = memberId.toBodyUuid()
        if (targetId == current.memberId) throw ConflictException("You cannot moderate yourself")

        val session =
            transaction {
                requireConferenceEligibleMembership(memberId = current.memberId)
                loadSpace(spaceId = id)
                requireSpaceModerator(spaceId = id, current = current)
                val open = EncounterSessions.openSession(spaceId = id) ?: throw ConflictException(SPACE_CLOSED_MESSAGE)
                // Only a real member can be moderated: an office holder must not be able to fill the bounded moderation list with random UUIDs.
                if (MemberTable
                        .selectAll()
                        .where { MemberTable.id eq targetId }
                        .limit(1)
                        .none()
                ) {
                    throw NotFoundException("Member not found")
                }
                if (!current.isPrivileged) {
                    // An office holder may act against the congregation only, never against another office holder or BOARD/ADMIN.
                    val targetIsProtected =
                        EncounterRoles.hasAnyRoleRow(spaceId = id, memberId = targetId) ||
                            AccountTable
                                .selectAll()
                                .where { (AccountTable.memberId eq targetId) and (AccountTable.role inList PRIVILEGED_ACCOUNT_ROLES) }
                                .limit(1)
                                .any()
                    if (targetIsProtected) throw ForbiddenException()
                }
                open
            }
        val roomId = session[ConferenceRoomTable.id]
        // The block/silence is recorded BEFORE the disconnect, so a failing LiveKit call can never leave the person free to re-enter.
        val recorded =
            if (silenceOnly) {
                moderationState.silence(sessionRoomId = roomId, memberId = targetId, privileged = current.isPrivileged)
            } else {
                moderationState.block(sessionRoomId = roomId, memberId = targetId, privileged = current.isPrivileged)
            }
        if (!recorded) throw ConflictException("The moderation list of this session is full")
        // V1.9.79: a removed person loses the seat (a silenced one keeps it and re-enters without a data channel).
        if (!silenceOnly) seatState.release(sessionRoomId = roomId, memberId = targetId)

        val roomName = session[ConferenceRoomTable.livekitRoomName]
        val live = liveKitCall { liveKitAdminClient.listParticipants(roomName) }.any { it.identity == targetId.toString() }
        if (live) liveKitCall { liveKitAdminClient.removeParticipant(room = roomName, identity = targetId.toString()) }
        transaction {
            // Same locks as an entry (space, then room): an entry that checked the block BEFORE it was recorded holds the room lock until it
            // commits, so this delete runs after it and removes the row it wrote -- otherwise a removed person could end up present.
            lockSpace(spaceId = id)
            ConferenceRoomTable
                .selectAll()
                .where { ConferenceRoomTable.id eq roomId }
                .forUpdate()
                .singleOrNull()
            ConferenceParticipationTable.deleteWhere {
                (ConferenceParticipationTable.roomId eq roomId) and (ConferenceParticipationTable.memberId eq targetId)
            }
        }
    }

    private fun mintTurnServers(
        memberId: Uuid,
        ttlMinutes: Long,
    ): List<ConferenceTurnServer> {
        if (!config.turnEnabled) return emptyList()
        val credential =
            TurnCredentialMinter.mint(
                sharedSecret = config.turnSharedSecret,
                urls = config.allTurnUrls,
                label = memberId.toString(),
                ttl = ttlMinutes.minutes,
            )
        return listOf(ConferenceTurnServer(urls = credential.urls, username = credential.username, credential = credential.credential))
    }

    private fun isListable(
        row: ResultRow,
        current: CurrentMember,
        status: MemberStatus,
    ): Boolean {
        if (row[EncounterSpaceTable.archivedAt] != null && !current.isPrivileged) return false
        return status !in MemberStatusSets.NON_MEMBER ||
            row[EncounterSpaceTable.guestPolicy] == EncounterGuestPolicy.MEMBERS_AND_GUESTS.name
    }

    /** The space, visible to the caller: an archived space is a [NotFoundException] for everybody but BOARD/ADMIN, a guest policy miss a [ForbiddenException]. */
    private fun loadVisibleSpace(
        spaceId: Uuid,
        current: CurrentMember,
        status: MemberStatus,
    ): ResultRow {
        val row = loadSpace(spaceId = spaceId)
        if (row[EncounterSpaceTable.archivedAt] != null &&
            !current.isPrivileged
        ) {
            throw NotFoundException("Encounter space $spaceId not found")
        }
        if (status in MemberStatusSets.NON_MEMBER && row[EncounterSpaceTable.guestPolicy] != EncounterGuestPolicy.MEMBERS_AND_GUESTS.name) {
            throw ForbiddenException("This encounter space does not admit non-members")
        }
        return row
    }

    private fun loadSpace(spaceId: Uuid): ResultRow =
        EncounterSpaceTable.selectAll().where { EncounterSpaceTable.id eq spaceId }.singleOrNull()
            ?: throw NotFoundException("Encounter space $spaceId not found")

    /** Lock order step 1: the space row `FOR UPDATE`. */
    private fun lockSpace(spaceId: Uuid): ResultRow =
        EncounterSpaceTable
            .selectAll()
            .where { EncounterSpaceTable.id eq spaceId }
            .forUpdate()
            .singleOrNull()
            ?: throw NotFoundException("Encounter space $spaceId not found")

    private fun dtoOf(
        spaceId: Uuid,
        current: CurrentMember,
    ): EncounterSpaceDto =
        EncounterSpaceViews.toDtos(rows = listOf(loadSpace(spaceId = spaceId)), current = current, config = config).single()

    private fun currentRoleRows(spaceId: Uuid): List<Pair<Uuid, String>> =
        EncounterSpaceRoleTable
            .selectAll()
            .where { EncounterSpaceRoleTable.spaceId eq spaceId }
            .map { it[EncounterSpaceRoleTable.memberId] to it[EncounterSpaceRoleTable.role] }

    private fun hasCurrentConsent(
        memberId: Uuid,
        text: EncounterConsentText,
    ): Boolean =
        EncounterConsentAcknowledgmentTable
            .selectAll()
            .where {
                (EncounterConsentAcknowledgmentTable.memberId eq memberId) and
                    (EncounterConsentAcknowledgmentTable.consentVersion eq text.version) and
                    (EncounterConsentAcknowledgmentTable.consentSha256 eq text.sha256)
            }.limit(1)
            .any()

    private fun disclaimerDto(text: EncounterConsentText) =
        EncounterConsentDisclaimerDto(
            version = text.version,
            headline = text.headline,
            keyPoints = text.keyPoints,
            text = text.text,
            sha256 = text.sha256,
        )

    /** Configuration is BOARD/ADMIN only (and an ACTIVE member). */
    private fun requireConfigAuthority(current: CurrentMember) {
        requireActiveMembership(memberId = current.memberId)
        if (!current.isPrivileged) throw ForbiddenException()
    }

    /** Opening, closing and moderating: an ACTIVE office holder of THIS space, or BOARD/ADMIN -- re-derived from the database. */
    private fun requireSpaceModerator(
        spaceId: Uuid,
        current: CurrentMember,
    ) {
        if (!current.isPrivileged && !EncounterRoles.isOfficer(spaceId = spaceId, memberId = current.memberId)) throw ForbiddenException()
    }

    private fun requireConferenceEnabled() {
        if (!config.enabled) {
            throw ConflictException(
                "Videokonferenzen is not configured on this server (LAPIS_LIVEKIT_URL/_API_KEY/_API_SECRET unset) -- see ConferenceConfig KDoc",
            )
        }
    }

    private fun requireWithinRate(
        limiter: FederationInboxRateLimiter,
        memberId: Uuid,
    ) {
        if (!limiter.checkAndRecord("member:$memberId")) throw ConflictException("Too many requests -- try again later")
    }

    /** Own helper, NOT [requireWithinRate]: that one throws [ConflictException], which here means "seat taken". */
    private fun requireSeatRate(
        limiter: FederationInboxRateLimiter,
        memberId: Uuid,
    ) {
        if (!limiter.checkAndRecord("member:$memberId")) throw ServiceBusyException("Too many requests")
    }

    private suspend fun <T> liveKitCall(action: suspend () -> T): T =
        try {
            action()
        } catch (e: LiveKitAdminException) {
            throw ConflictException("LiveKit request failed: ${e.message}")
        }

    private suspend fun deleteRoomBestEffort(livekitRoomName: String) {
        try {
            liveKitAdminClient.deleteRoom(livekitRoomName)
        } catch (e: LiveKitAdminException) {
            logger.warn { "could not delete an unused encounter LiveKit room -- it ends by LiveKit's own empty timeout" }
        }
    }

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime()

    private fun String.toSpaceUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id") }

    private fun String.toBodyUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw BadRequestException("Invalid member id") }

    private fun EncounterSpaceRole?.toPresenceRole(): EncounterPresenceRole =
        when (this) {
            EncounterSpaceRole.PULPIT -> EncounterPresenceRole.PULPIT
            EncounterSpaceRole.STEWARD -> EncounterPresenceRole.STEWARD
            null -> EncounterPresenceRole.CONGREGATION
        }

    private companion object {
        const val STATE_OPEN_SNAPSHOT = """{"state":"OPEN"}"""
        const val STATE_CLOSED_MANUAL_SNAPSHOT = """{"state":"CLOSED","reason":"MANUAL"}"""
    }
}
