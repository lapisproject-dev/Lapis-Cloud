package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.agreementtexts.AgreementTextResolver
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.server.encounter.EncounterRoles
import network.lapis.cloud.server.encounter.EncounterRoomGuard
import network.lapis.cloud.server.encounter.EncounterSessions
import network.lapis.cloud.server.encounter.PulpitRecordingCoordinator
import network.lapis.cloud.server.encounter.PulpitRecordingDocuments
import network.lapis.cloud.server.encounter.PulpitTarget
import network.lapis.cloud.server.encounter.profileOf
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.canAccessDocumentAtLevel
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.PulpitRecordingDocumentDto
import network.lapis.cloud.shared.domain.PulpitRecordingStateDto
import network.lapis.cloud.shared.domain.PulpitRecordingTextsDto
import network.lapis.cloud.shared.rpc.AgreementTextOutdatedException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IPulpitRecordingService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

// Privacy rule of this file (Art. 9 GDPR): the logs name nothing about a person, a room or a space.
private val logger = KotlinLogging.logger {}

/**
 * V1.9.143 -- see [IPulpitRecordingService]. This class is only the authorization layer; [PulpitRecordingCoordinator] does the work.
 *
 * **One gate** ([officerTargetInTx]) answers every precondition with the same bare [ForbiddenException]: instance switch off, (for a start) the notice
 * version not current, room option off, room archived, no open session, the session is no encounter session, the caller is no ACTIVE PULPIT/STEWARD of THIS
 * space, or the caller is not present. BOARD/ADMIN without an office have no access to the recording controls (their access is the document list).
 * Order everywhere: right, then rate limit, then the coordinator's lock; LiveKit is never called inside a transaction.
 *
 * Constructed per RPC request, so the coordinator and both limiters are constructor parameters WITHOUT a default (module singletons).
 */
class PulpitRecordingService(
    private val call: ApplicationCall,
    private val coordinator: PulpitRecordingCoordinator,
    private val controlRateLimiter: FederationInboxRateLimiter,
    private val readRateLimiter: FederationInboxRateLimiter,
    private val recordingInfrastructureAvailable: Boolean,
) : IPulpitRecordingService {
    override suspend fun pulpitRecordingState(spaceId: String): PulpitRecordingStateDto {
        val current = resolveCurrentMember(call)
        val id = spaceId.toUuidOrForbidden()
        val target = transaction { officerTargetInTx(spaceId = id, memberId = current.memberId, requirePulpit = false, forStart = false) }
        requireWithinRate(limiter = readRateLimiter, memberId = current.memberId)
        return stateDto(target = target, memberId = current.memberId)
    }

    override suspend fun pulpitRecordingTexts(spaceId: String): PulpitRecordingTextsDto {
        val current = resolveCurrentMember(call)
        val id = spaceId.toUuidOrForbidden()
        val target = transaction { officerTargetInTx(spaceId = id, memberId = current.memberId, requirePulpit = false, forStart = false) }
        requireWithinRate(limiter = readRateLimiter, memberId = current.memberId)
        return transaction {
            val pulpit = AgreementTextResolver.current(kind = AgreementTextKind.REC_PULPIT, profile = target.profile)
            val room = AgreementTextResolver.current(kind = AgreementTextKind.REC_ROOM, profile = target.profile)
            PulpitRecordingTextsDto(
                pulpitVersionLabel = pulpit.versionLabel,
                pulpitSha256 = pulpit.sha256,
                pulpitText = pulpit.body,
                roomText = room.body,
            )
        }
    }

    override suspend fun startPulpitRecording(spaceId: String): PulpitRecordingStateDto {
        val current = resolveCurrentMember(call)
        val id = spaceId.toUuidOrForbidden()
        val target = transaction { officerTargetInTx(spaceId = id, memberId = current.memberId, requirePulpit = false, forStart = true) }
        requireWithinRate(limiter = controlRateLimiter, memberId = current.memberId)
        coordinator.startRecording(target = target, actor = current) {
            // Re-derived in the final transaction, after the egress started: nothing may have changed meanwhile.
            val again = officerTargetInTx(spaceId = id, memberId = current.memberId, requirePulpit = false, forStart = true)
            if (again.roomId != target.roomId) throw ForbiddenException()
        }
        return stateDto(target = target, memberId = current.memberId)
    }

    override suspend fun stopPulpitRecording(spaceId: String): PulpitRecordingStateDto {
        val current = resolveCurrentMember(call)
        val id = spaceId.toUuidOrForbidden()
        // A recording with a stale notice version (or even a switched-off instance) must still be stoppable: only the office and presence are checked.
        val target =
            transaction {
                officerTargetInTx(
                    spaceId = id,
                    memberId = current.memberId,
                    requirePulpit = false,
                    forStart = false,
                    forStop = true,
                )
            }
        requireWithinRate(limiter = controlRateLimiter, memberId = current.memberId)
        coordinator.stopRecording(roomId = target.roomId, actor = current)
        return stateDto(target = target, memberId = current.memberId)
    }

    override suspend fun confirmPulpitRecording(
        spaceId: String,
        versionLabel: String,
        sha256: String,
    ): PulpitRecordingStateDto {
        val current = resolveCurrentMember(call)
        val id = spaceId.toUuidOrForbidden()
        val target = transaction { officerTargetInTx(spaceId = id, memberId = current.memberId, requirePulpit = true, forStart = true) }
        requireWithinRate(limiter = controlRateLimiter, memberId = current.memberId)
        val matches =
            transaction {
                AgreementTextResolver.matchesCurrent(
                    kind = AgreementTextKind.REC_PULPIT,
                    version = versionLabel,
                    sha256 = sha256,
                    profile = target.profile,
                )
            }
        if (!matches) throw AgreementTextOutdatedException()
        coordinator.confirm(target = target, memberId = current.memberId, checkedPulpitTextSha256 = sha256) {
            officerTargetInTx(spaceId = id, memberId = current.memberId, requirePulpit = true, forStart = true)
        }
        return stateDto(target = target, memberId = current.memberId)
    }

    override suspend fun declinePulpitRecording(spaceId: String) {
        val current = resolveCurrentMember(call)
        val id = spaceId.toUuidOrForbidden()
        val target = transaction { officerTargetInTx(spaceId = id, memberId = current.memberId, requirePulpit = true, forStart = false) }
        requireWithinRate(limiter = controlRateLimiter, memberId = current.memberId)
        coordinator.decline(roomId = target.roomId, memberId = current.memberId)
    }

    override suspend fun listPulpitRecordings(spaceId: String?): List<PulpitRecordingDocumentDto> {
        val current = resolveCurrentMember(call)
        requireDocumentAuthority(current)
        requireWithinRate(limiter = readRateLimiter, memberId = current.memberId)
        val id = spaceId?.let { runCatching { Uuid.parse(it) }.getOrElse { throw NotFoundException("Invalid id") } }
        return transaction { PulpitRecordingDocuments.list(id) }
    }

    override suspend fun deletePulpitRecording(documentId: String): Boolean {
        val current = resolveCurrentMember(call)
        requireDocumentAuthority(current)
        requireWithinRate(limiter = controlRateLimiter, memberId = current.memberId)
        val id = runCatching { Uuid.parse(documentId) }.getOrElse { throw NotFoundException("Invalid id") }
        val deleted = PulpitRecordingDocuments.hardDelete(documentId = id, actor = current)
        if (!deleted) throw NotFoundException("Recording not found")
        logger.info { "pulpit recording deleted" }
        return true
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** BOARD/ADMIN, and entitled to the level the recordings are stored at (the same predicate as the document list). */
    private fun requireDocumentAuthority(current: CurrentMember) {
        if (!current.isPrivileged || !current.canAccessDocumentAtLevel(DocumentAccessLevel.BOARD_ONLY)) throw ForbiddenException()
    }

    private fun stateDto(
        target: PulpitTarget,
        memberId: Uuid,
    ): PulpitRecordingStateDto {
        val view = coordinator.view(roomId = target.roomId, callerId = memberId)
        return PulpitRecordingStateDto(
            active = view.active,
            startable = recordingInfrastructureAvailable && !view.active,
            confirmationRequired = target.requireConfirmation,
            confirmedByMe = view.confirmedByMe,
            pendingConfirmation = view.pendingConfirmation,
            maxReached = view.maxReached,
        )
    }

    /**
     * The one gate, inside a transaction. Every refusal is the same bare [ForbiddenException].
     *
     * @param requirePulpit the caller must hold the PULPIT office (a STEWARD does not qualify)
     * @param forStart the CURRENT version of the operator notice must have been acknowledged (a new recording needs it, a running one does not)
     * @param forStop stopping must stay possible when the instance switch or the room option was turned off meanwhile
     */
    private fun officerTargetInTx(
        spaceId: Uuid,
        memberId: Uuid,
        requirePulpit: Boolean,
        forStart: Boolean,
        forStop: Boolean = false,
    ): PulpitTarget {
        val settings = PulpitRecordingSettingsStore.load()
        if (!forStop) {
            if (!settings.enabled) throw ForbiddenException()
            if (forStart && !settings.startAllowed()) throw ForbiddenException()
        }
        val space = EncounterSpaceTable.selectAll().where { EncounterSpaceTable.id eq spaceId }.singleOrNull() ?: throw ForbiddenException()
        if (space[EncounterSpaceTable.archivedAt] != null) throw ForbiddenException()
        if (!forStop && !space[EncounterSpaceTable.pulpitRecordingAllowed]) throw ForbiddenException()
        val role = EncounterRoles.roleOf(spaceId = spaceId, memberId = memberId) ?: throw ForbiddenException()
        if (requirePulpit && role != EncounterSpaceRole.PULPIT) throw ForbiddenException()
        val session = EncounterSessions.openSession(spaceId = spaceId) ?: throw ForbiddenException()
        // Only an encounter session (a conference room with an encounter space) qualifies; an ordinary conference room never does.
        if (!EncounterRoomGuard.isEncounterRoom(session)) throw ForbiddenException()
        val roomId = session[ConferenceRoomTable.id]
        val present =
            ConferenceParticipationTable
                .selectAll()
                .where { (ConferenceParticipationTable.roomId eq roomId) and (ConferenceParticipationTable.memberId eq memberId) }
                .limit(1)
                .any()
        if (!present) throw ForbiddenException()
        return PulpitTarget(
            spaceId = spaceId,
            roomId = roomId,
            livekitRoomName = session[ConferenceRoomTable.livekitRoomName],
            profile = profileOf(space),
            requireConfirmation = settings.requireConfirmation,
        )
    }

    private fun requireWithinRate(
        limiter: FederationInboxRateLimiter,
        memberId: Uuid,
    ) {
        if (!limiter.checkAndRecord("member:$memberId")) throw ConflictException("Too many requests -- try again later")
    }

    private fun String.toUuidOrForbidden(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw ForbiddenException() }
}
