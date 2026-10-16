package network.lapis.cloud.server.encounter

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.agreementtexts.AgreementTextHash
import network.lapis.cloud.server.agreementtexts.AgreementTextResolver
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.conference.ConferenceRecordingConfig
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.conference.LiveKitEgressClient
import network.lapis.cloud.server.conference.LiveKitEgressInfo
import network.lapis.cloud.server.conference.LiveKitParticipantInfo
import network.lapis.cloud.server.conference.MAX_SEND_DATA_DESTINATIONS
import network.lapis.cloud.server.conference.RecordingDiskBudget
import network.lapis.cloud.server.conference.RecordingEgressPaths
import network.lapis.cloud.server.conference.RecordingMediaProber
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.server.rpc.PulpitRecordingSettingsStore
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ENCOUNTER_RECORDING_TOPIC
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterRecordingSignal
import network.lapis.cloud.shared.domain.encounterRecordingPayload
import network.lapis.cloud.shared.rpc.AgreementTextOutdatedException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.PulpitRecordingAwaitingConfirmationException
import network.lapis.cloud.shared.rpc.PulpitRecordingFloorActiveException
import network.lapis.cloud.shared.rpc.PulpitRecordingLimitReachedException
import network.lapis.cloud.shared.rpc.PulpitRecordingUnavailableException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// Privacy rule of this file (Art. 9 GDPR): the logs name counts and exception classes only -- never a member, an identity, a display name, a
// room or space id, a title or a path. `PulpitRecordingLogTripwireTest` and `EncounterPrivacyTripwireTest` scan for exactly that.
private val logger = KotlinLogging.logger {}

/** At most this many pulpit files are recorded at the same time on the whole server (a sibling of the per-room limit). */
internal const val MAX_ACTIVE_PULPIT_EGRESSES = 8

private val TERMINAL_EGRESS_STATUSES = setOf("EGRESS_COMPLETE", "EGRESS_FAILED", "EGRESS_ABORTED", "EGRESS_LIMIT_REACHED")

/** Gives up archiving a finished file after this many passes (15 s each = about ten minutes); the file stays on the volume. */
private const val MAX_FINALIZING_ATTEMPTS = 40

/** Egresses of rooms that ended within this window are still swept after a restart. */
private const val ENDED_RECHECK_HOURS = 2

/** One encounter session as the service resolved it, inside its transaction. */
internal data class PulpitTarget(
    val spaceId: Uuid,
    val roomId: Uuid,
    val livekitRoomName: String,
    val profile: EncounterProfile,
    val requireConfirmation: Boolean,
)

/** What a caller sees of the recording of a session. */
internal data class PulpitRecordingView(
    val active: Boolean,
    val pendingConfirmation: Boolean,
    val maxReached: Boolean,
    val confirmedByMe: Boolean,
)

/**
 * V1.9.143 -- the engine of the optional pulpit recording: starting and stopping the participant file egress of the PULPIT person(s), the
 * confirmation of the pulpit, the "recording" packets, and the clock that makes sure a recording never outlives its preconditions. Module singleton
 * (the in-memory [PulpitRecordingState] must not be recreated per request).
 *
 * **Who is recorded.** Only [PulpitRecordingSources.select] decides, from the database and the LiveKit roster; never a client parameter. The
 * congregation is not in a participant egress: it records only the published tracks of ONE identity.
 *
 * **LiveKit calls never run inside a `transaction {}`** (prepare / act / re-verify). The re-check and the audit entry of a start run in one
 * short transaction after the egress started; if the re-check fails, the egress is stopped and its file is discarded.
 *
 * **The banner may never say "off" while a recording runs.** A failing LiveKit therefore never clears the state: the recording stays "active"
 * until LiveKit confirms (or the egress is known to be terminal). Every lifecycle step is serialised by [mutex].
 */
class PulpitRecordingCoordinator(
    private val liveKitAdminClient: LiveKitAdminClient,
    private val liveKitEgressClient: LiveKitEgressClient,
    private val recordingConfig: ConferenceRecordingConfig,
    private val liveKitEnabled: Boolean,
    private val state: PulpitRecordingState,
    private val floorState: EncounterFloorState,
    private val documentStorageRoot: File,
    private val prober: RecordingMediaProber = RecordingMediaProber.None,
    private val intervalSeconds: Long = 15,
    private val diskSpace: (File) -> Long = { it.usableSpace },
    private val clock: () -> LocalDateTime = { DbClock.nowLocalDateTime() },
) : PulpitRecordingControl {
    private val mutex = Mutex()
    private var job: Job? = null
    private var startupSweepDone = false
    private var lastOrphanFileCheck: LocalDateTime? = null

    /** Idempotent. Runs one [tick] immediately (the start-up sweep), then every [intervalSeconds]. */
    fun start() {
        if (!liveKitEnabled || job?.isActive == true) return
        job =
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                while (isActive) {
                    tick()
                    delay(intervalSeconds.seconds)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    // ── Reads ────────────────────────────────────────────────────────────

    internal fun view(
        roomId: Uuid,
        callerId: Uuid,
    ): PulpitRecordingView {
        val session = state.session(roomId)
        return PulpitRecordingView(
            active = session != null,
            pendingConfirmation = state.hasPending(roomId),
            maxReached = session?.maxReached == true,
            confirmedByMe = callerId in state.confirmedSet(roomId),
        )
    }

    internal fun isActive(roomId: Uuid): Boolean = state.session(roomId) != null

    override fun isRecordingActive(sessionRoomId: Uuid): Boolean = isActive(sessionRoomId)

    // ── Start ────────────────────────────────────────────────────────────

    /**
     * Starts the recording of the session in [target]. [reverify] runs inside the final transaction (before the audit entry) and must throw
     * the service's bare Forbidden if any precondition changed while the egress was starting.
     *
     * @throws PulpitRecordingUnavailableException no recording service, no disk space, or LiveKit not reachable
     * @throws PulpitRecordingFloorActiveException somebody of the congregation holds the floor
     * @throws PulpitRecordingLimitReachedException the server-wide limit of simultaneous pulpit files
     * @throws PulpitRecordingAwaitingConfirmationException the pulpit has been asked and has not confirmed yet
     * @throws ConflictException no pulpit person is connected
     */
    internal suspend fun startRecording(
        target: PulpitTarget,
        actor: CurrentMember,
        reverify: () -> Unit,
    ) {
        mutex.withLock {
            if (!liveKitEnabled || !recordingConfig.enabled) throw PulpitRecordingUnavailableException()
            if (!diskHasRoom()) throw PulpitRecordingUnavailableException()
            if (floorState.current(target.roomId) != null) throw PulpitRecordingFloorActiveException()
            val participants = rosterOrUnavailable(target.livekitRoomName)
            val existing = state.session(target.roomId)
            val texts = transaction { currentTexts(target.profile) }
            state.invalidateConfirmationsIfTextChanged(roomId = target.roomId, currentPulpitTextSha256 = texts.second)
            val selection =
                transaction {
                    PulpitRecordingSources.select(
                        spaceId = target.spaceId,
                        roomId = target.roomId,
                        liveIdentities = participants.map { it.identity }.toSet(),
                        confirmed = state.confirmedSet(target.roomId),
                        requireConfirmation = target.requireConfirmation,
                        alreadyRecorded = existing?.egresses?.keys ?: emptySet(),
                    )
                }
            if (selection.unconfirmed.isNotEmpty()) {
                if (!state.setPending(
                        roomId = target.roomId,
                        spaceId = target.spaceId,
                        livekitRoomName = target.livekitRoomName,
                        actorMemberId = actor.memberId,
                        actorRole = actor.role,
                    )
                ) {
                    throw PulpitRecordingLimitReachedException()
                }
                send(
                    livekitRoomName = target.livekitRoomName,
                    identities = selection.unconfirmed.map { it.toString() },
                    signal = EncounterRecordingSignal(active = existing != null, ask = true),
                )
            }
            if (selection.sources.isEmpty()) {
                if (selection.unconfirmed.isNotEmpty()) throw PulpitRecordingAwaitingConfirmationException()
                throw ConflictException("No pulpit person is connected")
            }
            val missing = selection.sources.filter { it !in (existing?.egresses?.keys ?: emptySet()) }
            if (existing != null && missing.isEmpty()) return
            if (state.activeEgressCount() + missing.size > MAX_ACTIVE_PULPIT_EGRESSES) throw PulpitRecordingLimitReachedException()

            val session =
                state.getOrCreate(roomId = target.roomId) {
                    PulpitRecordingState.Session(
                        spaceId = target.spaceId,
                        livekitRoomName = target.livekitRoomName,
                        actorMemberId = actor.memberId,
                        roomTextSha256 = texts.first,
                        pulpitTextSha256 = texts.second,
                    )
                } ?: throw PulpitRecordingLimitReachedException()
            val created = existing == null
            val started = mutableListOf<PulpitRecordingState.Egress>()
            try {
                missing.forEach { started += startEgressFor(target = target, memberId = it) }
                val pulpitConfirmed = missing.any { it in state.confirmedSet(target.roomId) }
                transaction {
                    reverify()
                    AuditLogRecorder.record(
                        actorMemberId = actor.memberId,
                        actorRole = actor.role,
                        entityType = AuditEntityType.PULPIT_RECORDING,
                        entityId = target.spaceId,
                        action = if (created) AuditAction.CREATE else AuditAction.UPDATE,
                        after =
                            """{"event":"${if (created) "STARTED" else "SOURCE_ADDED"}","pulpitConfirmed":$pulpitConfirmed,""" +
                                """"confirmationRequired":${target.requireConfirmation},"roomTextSha256":"${session.roomTextSha256}",""" +
                                """"pulpitTextSha256":"${session.pulpitTextSha256}",""" +
                                """"noticeVersion":"${PulpitRecordingSettingsStore.load().noticeVersion.orEmpty()}"}""",
                    )
                }
            } catch (e: Throwable) {
                withContext(NonCancellable) {
                    started.forEach { discardStartedEgress(target = target, egress = it, uploader = actor.memberId) }
                    if (created && session.egresses.isEmpty()) state.remove(target.roomId)
                }
                if (e is LiveKitAdminException) throw PulpitRecordingUnavailableException()
                throw e
            }
            started.forEach { session.egresses[it.memberId] = it }
            session.maxReached = selection.capped
            state.clearPending(target.roomId)
            broadcast(livekitRoomName = target.livekitRoomName, signal = EncounterRecordingSignal(active = true))
            session.notified.clear()
            participants.forEach { session.notified += notifiedKey(it) }
            logger.info { "pulpit recording started (${session.egresses.size} file(s))" }
        }
    }

    /** Whether the volumes have room for one more pulpit file (the same budget as the start request). */
    private fun diskHasRoom(): Boolean =
        RecordingDiskBudget.hasRoom(
            workRoot = File(recordingConfig.outputHostDir),
            documentRoot = documentStorageRoot,
            rawBytes = 0L,
            durationSeconds = 0L,
            currentFileBytes = null,
            diskSpace = diskSpace,
        )

    /** One participant file egress for [memberId]; the path never contains an identity. */
    private suspend fun startEgressFor(
        target: PulpitTarget,
        memberId: Uuid,
    ): PulpitRecordingState.Egress {
        val fileId = Uuid.random()
        val path =
            RecordingEgressPaths.pulpitFile(
                containerDir = recordingConfig.outputContainerDir,
                spaceId = target.spaceId,
                fileId = fileId,
            )
        val info =
            liveKitEgressClient.startParticipantFileEgress(
                roomName = target.livekitRoomName,
                identity = memberId.toString(),
                outputFilepath = path,
            )
        return PulpitRecordingState.Egress(
            memberId = memberId,
            egressId = info.egressId,
            fileId = fileId,
            startedAt = state.nowMark(),
            startedAtWall = clock(),
        )
    }

    /** Stops an egress whose start could not be confirmed and queues its file for deletion. Never throws. */
    private suspend fun discardStartedEgress(
        target: PulpitTarget,
        egress: PulpitRecordingState.Egress,
        uploader: Uuid,
    ) {
        runCatching { liveKitEgressClient.stopEgress(roomName = target.livekitRoomName, egressId = egress.egressId) }
        state.addFinalizing(
            PulpitRecordingState.Finalizing(
                spaceId = target.spaceId,
                livekitRoomName = target.livekitRoomName,
                egressId = egress.egressId,
                fileId = egress.fileId,
                startedAtWall = egress.startedAtWall,
                uploaderMemberId = uploader,
                stoppedAt = state.nowMark(),
                discard = true,
            ),
        )
    }

    // ── Stop ─────────────────────────────────────────────────────────────

    /** A person pressed "stop". Idempotent. Throws [PulpitRecordingUnavailableException] if LiveKit would not stop an egress (it keeps running, the banner stays on). */
    internal suspend fun stopRecording(
        roomId: Uuid,
        actor: CurrentMember,
    ) {
        mutex.withLock {
            val session = state.session(roomId) ?: return
            val failed =
                stopEgressesLocked(
                    roomId = roomId,
                    session = session,
                    targets = session.egresses.values.toList(),
                    reason = null,
                    actor = actor,
                )
            if (failed > 0) throw PulpitRecordingUnavailableException()
        }
    }

    override suspend fun stopAll(reason: PulpitStopReason) {
        mutex.withLock { state.sessionIds().forEach { stopSessionLocked(roomId = it, reason = reason) } }
    }

    override suspend fun stopSession(
        sessionRoomId: Uuid,
        reason: PulpitStopReason,
    ) {
        mutex.withLock { stopSessionLocked(roomId = sessionRoomId, reason = reason) }
    }

    override suspend fun stopSpace(
        spaceId: Uuid,
        reason: PulpitStopReason,
    ) {
        mutex.withLock { state.sessionsOfSpace(spaceId).forEach { (roomId, _) -> stopSessionLocked(roomId = roomId, reason = reason) } }
    }

    override suspend fun reconcileSpace(spaceId: Uuid) {
        mutex.withLock {
            state.sessionsOfSpace(spaceId).forEach { (roomId, session) ->
                try {
                    dropNonPulpitSourcesLocked(roomId = roomId, session = session)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn { "pulpit recording: the offices could not be re-checked (${e::class.simpleName})" }
                }
            }
        }
    }

    /** Stops every egress of a session and ends it. Never throws (a failure is logged and retried by the next [tick]). */
    private suspend fun stopSessionLocked(
        roomId: Uuid,
        reason: PulpitStopReason,
    ) {
        val session = state.session(roomId) ?: return
        try {
            stopEgressesLocked(
                roomId = roomId,
                session = session,
                targets = session.egresses.values.toList(),
                reason = reason,
                actor = null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn { "pulpit recording: a session could not be stopped completely (${e::class.simpleName})" }
        }
    }

    /**
     * Stops [targets] (egresses of [session]); those LiveKit confirmed move to the archive queue. When no egress is left the session ends: the banner is
     * switched off and one audit entry is written (the acting person for a stop by hand, none for an automatic one). Returns the number of egresses
     * that are still running because LiveKit did not confirm the stop.
     */
    private suspend fun stopEgressesLocked(
        roomId: Uuid,
        session: PulpitRecordingState.Session,
        targets: List<PulpitRecordingState.Egress>,
        reason: PulpitStopReason?,
        actor: CurrentMember?,
    ): Int {
        var stillRunning = 0
        targets.forEach { egress ->
            if (stopEgressConfirmed(livekitRoomName = session.livekitRoomName, egressId = egress.egressId)) {
                session.egresses.remove(egress.memberId)
                state.addFinalizing(
                    PulpitRecordingState.Finalizing(
                        spaceId = session.spaceId,
                        livekitRoomName = session.livekitRoomName,
                        egressId = egress.egressId,
                        fileId = egress.fileId,
                        startedAtWall = egress.startedAtWall,
                        uploaderMemberId = session.actorMemberId,
                        stoppedAt = state.nowMark(),
                    ),
                )
            } else {
                stillRunning++
            }
        }
        // The recording ends with its last file: the banner goes off, one audit entry is written. (A file that ends on its own because the person
        // lost the connection is handled by the clock and keeps the session armed -- that is not a stop.) While a file is still running because
        // LiveKit did not confirm its stop, the session stays: the banner never says "off" while something may still be recorded.
        if (session.egresses.isEmpty()) {
            state.remove(roomId)
            state.clearConfirmations(roomId)
            broadcast(livekitRoomName = session.livekitRoomName, signal = EncounterRecordingSignal(active = false))
            writeStopAudit(spaceId = session.spaceId, reason = reason, actor = actor)
            logger.info { "pulpit recording stopped (${reason?.name ?: "BY_HAND"})" }
        } else if (targets.isNotEmpty() && stillRunning < targets.size) {
            writeStopAudit(spaceId = session.spaceId, reason = reason, actor = actor)
        }
        return stillRunning
    }

    /** `true` iff LiveKit stopped [egressId] or reports it terminal / unknown; `false` if that could not be established (then it must be assumed to run). */
    private suspend fun stopEgressConfirmed(
        livekitRoomName: String,
        egressId: String,
    ): Boolean {
        try {
            liveKitEgressClient.stopEgress(roomName = livekitRoomName, egressId = egressId)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: LiveKitAdminException) {
            // The stop was refused or never arrived: ask whether the egress is over anyway (the room may be gone, which ends its egresses).
            return try {
                val info = liveKitEgressClient.listEgress(livekitRoomName).firstOrNull { it.egressId == egressId }
                info == null || info.status in TERMINAL_EGRESS_STATUSES
            } catch (e2: LiveKitAdminException) {
                false
            }
        }
    }

    private fun writeStopAudit(
        spaceId: Uuid,
        reason: PulpitStopReason?,
        actor: CurrentMember?,
    ) {
        try {
            transaction {
                AuditLogRecorder.record(
                    actorMemberId = actor?.memberId,
                    actorRole = actor?.role,
                    entityType = AuditEntityType.PULPIT_RECORDING,
                    entityId = spaceId,
                    action = AuditAction.UPDATE,
                    after =
                        if (reason == null) {
                            """{"event":"STOPPED"}"""
                        } else {
                            """{"event":"STOPPED_AUTOMATICALLY","reason":"${reason.name}"}"""
                        },
                )
            }
        } catch (e: Exception) {
            // The recording is already stopped; an audit failure must never bring it back or throw into the caller.
            logger.error { "pulpit recording: the stop could not be audited (${e::class.simpleName})" }
        }
    }

    // ── Confirmation ─────────────────────────────────────────────────────

    /**
     * A PULPIT person confirmed. Remembered for the session only. If a start request is open, or the recording is already running, the person's own file
     * starts now (subject to the per-room and server-wide limits). Never throws a limit exception to the confirming person: they have said yes, the
     * recording tick retries.
     */
    internal suspend fun confirm(
        target: PulpitTarget,
        memberId: Uuid,
        checkedPulpitTextSha256: String,
        reverify: () -> Unit,
    ) {
        mutex.withLock {
            val pulpitSha = transaction { currentTexts(target.profile).second }
            // The agreement is bound to the text the person saw and the caller checked: a text published in between is never agreed to unseen.
            if (!AgreementTextHash.matches(
                    expectedSha256 = pulpitSha,
                    providedSha256 = checkedPulpitTextSha256,
                )
            ) {
                throw AgreementTextOutdatedException()
            }
            // An agreement is only meaningful for a recording that is running or asked for. Without either, nothing is stored (it would otherwise
            // linger and start a later recording without a new dialog).
            if (state.session(target.roomId) == null && state.pendingActorInfo(target.roomId) == null) return
            if (!state.confirm(roomId = target.roomId, memberId = memberId, pulpitTextSha256 = pulpitSha)) {
                throw PulpitRecordingLimitReachedException()
            }
            val pending = state.pendingActorInfo(target.roomId)
            if (state.session(target.roomId) != null || pending != null) {
                val participants = runCatching { liveKitAdminClient.listParticipants(target.livekitRoomName) }.getOrNull() ?: return
                val session = state.session(target.roomId)
                val actorMemberId = session?.actorMemberId ?: pending?.memberId ?: return
                fillSourcesLocked(
                    target = target,
                    session = session,
                    participants = participants,
                    actorMemberId = actorMemberId,
                    reverify = reverify,
                    auditActor = pending,
                )
            }
        }
    }

    /**
     * The pulpit person declines or withdraws: the open request is discarded, an earlier agreement is removed, and the person's own running file (if
     * any) is stopped. Without a new agreement they are not recorded again.
     */
    internal suspend fun decline(
        roomId: Uuid,
        memberId: Uuid,
    ) {
        mutex.withLock {
            state.clearPending(roomId)
            state.withdraw(roomId = roomId, memberId = memberId)
            val session = state.session(roomId) ?: return
            val own = session.egresses[memberId] ?: return
            stopEgressesLocked(
                roomId = roomId,
                session = session,
                targets = listOf(own),
                reason = PulpitStopReason.CONSENT_WITHDRAWN,
                actor = null,
            )
        }
    }

    /**
     * Starts the files of connected, confirmed PULPIT persons that are not recorded yet -- the second half of [confirm] and of the late-arrival rule of
     * [tick]. A start request that nobody completed (the actor left, the room changed) simply does nothing.
     *
     * [auditActor] is the person who requested the start (only on the confirm path of a start request, where the audit entry of the start is written
     * here instead of in [startRecording]); the clock's late-arrival path passes none, because nobody acted.
     */
    private suspend fun fillSourcesLocked(
        target: PulpitTarget,
        session: PulpitRecordingState.Session?,
        participants: List<LiveKitParticipantInfo>,
        actorMemberId: Uuid,
        reverify: () -> Unit,
        auditActor: PulpitRecordingState.PendingActor? = null,
    ) {
        if (!recordingConfig.enabled || floorState.current(target.roomId) != null) return
        val selection =
            transaction {
                PulpitRecordingSources.select(
                    spaceId = target.spaceId,
                    roomId = target.roomId,
                    liveIdentities = participants.map { it.identity }.toSet(),
                    confirmed = state.confirmedSet(target.roomId),
                    requireConfirmation = target.requireConfirmation,
                    alreadyRecorded = session?.egresses?.keys ?: emptySet(),
                )
            }
        val missing = selection.sources.filter { it !in (session?.egresses?.keys ?: emptySet()) }
        if (missing.isEmpty() || state.activeEgressCount() + missing.size > MAX_ACTIVE_PULPIT_EGRESSES) return
        // The same disk check as the start request: the volume may have filled since (up to two minutes of confirmation, or an armed session).
        if (!diskHasRoom()) {
            logger.warn { "pulpit recording: no disk space -- a further file was not started" }
            return
        }
        val texts = transaction { currentTexts(target.profile) }
        val live =
            session
                ?: state.getOrCreate(roomId = target.roomId) {
                    PulpitRecordingState.Session(
                        spaceId = target.spaceId,
                        livekitRoomName = target.livekitRoomName,
                        actorMemberId = actorMemberId,
                        roomTextSha256 = texts.first,
                        pulpitTextSha256 = texts.second,
                    )
                }
                ?: return
        val created = session == null
        val started = mutableListOf<PulpitRecordingState.Egress>()
        try {
            missing.forEach { started += startEgressFor(target = target, memberId = it) }
            transaction {
                reverify()
                AuditLogRecorder.record(
                    actorMemberId = auditActor?.memberId,
                    actorRole = auditActor?.role,
                    entityType = AuditEntityType.PULPIT_RECORDING,
                    entityId = target.spaceId,
                    action = if (created) AuditAction.CREATE else AuditAction.UPDATE,
                    after =
                        """{"event":"${if (created) "STARTED" else "SOURCE_ADDED"}","pulpitConfirmed":true,""" +
                            """"confirmationRequired":${target.requireConfirmation},"roomTextSha256":"${live.roomTextSha256}",""" +
                            """"pulpitTextSha256":"${live.pulpitTextSha256}",""" +
                            """"noticeVersion":"${PulpitRecordingSettingsStore.load().noticeVersion.orEmpty()}"}""",
                )
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                started.forEach { discardStartedEgress(target = target, egress = it, uploader = actorMemberId) }
                if (created && live.egresses.isEmpty()) state.remove(target.roomId)
            }
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) {
                started.forEach { discardStartedEgress(target = target, egress = it, uploader = actorMemberId) }
                if (created && live.egresses.isEmpty()) state.remove(target.roomId)
            }
            logger.warn { "pulpit recording: a further file could not be started (${e::class.simpleName})" }
            return
        }
        started.forEach { live.egresses[it.memberId] = it }
        live.maxReached = selection.capped
        state.clearPending(target.roomId)
        if (created) {
            broadcast(livekitRoomName = target.livekitRoomName, signal = EncounterRecordingSignal(active = true))
            live.notified.clear()
            participants.forEach { live.notified += notifiedKey(it) }
        }
    }

    // ── The clock ────────────────────────────────────────────────────────

    /** One full run. Never throws (except for coroutine cancellation). */
    suspend fun tick() {
        if (!liveKitEnabled) return
        mutex.withLock {
            try {
                if (!startupSweepDone) {
                    startupSweepDone = startupSweepLocked()
                }
                supervisionLocked()
                finalizeLocked()
                orphanFileCheckLocked()
            } catch (e: CancellationException) {
                throw e
            } catch (e: LiveKitAdminException) {
                logger.warn { "pulpit recording: LiveKit is not reachable -- the state is kept" }
            } catch (e: Exception) {
                logger.error { "pulpit recording: the run failed (${e::class.simpleName})" }
            }
        }
    }

    /** `(session room, space, LiveKit room name, profile, require confirmation)` of every open encounter session. */
    private fun openSessions(): List<PulpitTarget> =
        transaction {
            val settings = PulpitRecordingSettingsStore.load()
            ConferenceRoomTable
                .selectAll()
                .where { ConferenceRoomTable.encounterSpaceId.isNotNull() and ConferenceRoomTable.endedAt.isNull() }
                .mapNotNull { room ->
                    val spaceId = room[ConferenceRoomTable.encounterSpaceId] ?: return@mapNotNull null
                    val space =
                        EncounterSpaceTable.selectAll().where { EncounterSpaceTable.id eq spaceId }.singleOrNull() ?: return@mapNotNull null
                    PulpitTarget(
                        spaceId = spaceId,
                        roomId = room[ConferenceRoomTable.id],
                        livekitRoomName = room[ConferenceRoomTable.livekitRoomName],
                        profile = profileOf(space),
                        requireConfirmation = settings.requireConfirmation,
                    )
                }
        }

    /**
     * After a restart nothing is known of the egresses that ran: stop every file egress in an encounter room that the state does not own, and switch
     * every open room's banner off. A file egress in an encounter room can only be a pulpit recording (the ordinary recording is fenced off from
     * encounter rooms), so this never touches a stream or a conference recording.
     *
     * Returns `true` only when EVERY room could be listed and every orphan stopped. A room whose egress list (or whose stop) failed is retried by the
     * next [tick] (the sweep is not marked done) and is NOT told "off" meanwhile: the banner never says "off" while an orphan may still record.
     */
    private suspend fun startupSweepLocked(): Boolean {
        val open = openSessions()
        val now = clock()
        val recentlyEnded =
            transaction {
                val since = (now.toInstant(ServerClock.zone) - ENDED_RECHECK_HOURS.hours).toLocalDateTime(ServerClock.zone)
                ConferenceRoomTable
                    .selectAll()
                    .where {
                        ConferenceRoomTable.encounterSpaceId.isNotNull() and ConferenceRoomTable.endedAt.isNotNull() and
                            (ConferenceRoomTable.endedAt greater since)
                    }.map { it[ConferenceRoomTable.livekitRoomName] }
            }
        var stopped = 0
        val stoppedInRooms = mutableSetOf<String>()
        val uncertainRooms = mutableSetOf<String>()
        (open.map { it.livekitRoomName } + recentlyEnded).distinct().forEach { roomName ->
            val infos =
                try {
                    liveKitEgressClient.listEgress(roomName)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    uncertainRooms += roomName
                    return@forEach
                }
            infos
                .filter { isOrphanPulpitEgress(it) }
                .forEach { info ->
                    try {
                        liveKitEgressClient.stopEgress(roomName = roomName, egressId = info.egressId)
                        stopped++
                        stoppedInRooms += roomName
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        uncertainRooms += roomName
                    }
                }
        }
        if (stopped > 0) logger.warn { "pulpit recording: $stopped orphaned file egress(es) stopped after a restart" }
        // Every open room whose recording this process does not own hears "off" (a banner left over from before the restart goes away); a room
        // whose recording IS owned (the clock started late) is left alone. The audit entry is written for the rooms where something was stopped.
        open.filter { state.session(it.roomId) == null }.forEach { target ->
            if (target.livekitRoomName in stoppedInRooms) {
                writeStopAudit(spaceId = target.spaceId, reason = PulpitStopReason.RESTART_ORPHAN, actor = null)
            }
            if (target.livekitRoomName !in uncertainRooms) {
                broadcast(livekitRoomName = target.livekitRoomName, signal = EncounterRecordingSignal(active = false))
            }
        }
        if (uncertainRooms.isNotEmpty()) {
            logger.warn { "pulpit recording: the start-up sweep is incomplete (${uncertainRooms.size} room(s)) and is retried" }
        }
        return uncertainRooms.isEmpty()
    }

    private fun isOrphanPulpitEgress(info: LiveKitEgressInfo): Boolean =
        info.status !in TERMINAL_EGRESS_STATUSES &&
            info.streamResults.isEmpty() &&
            info.fileResults.isNotEmpty() &&
            state.sessionIds().none { id ->
                state
                    .session(id)
                    ?.egresses
                    ?.values
                    ?.any { it.egressId == info.egressId } == true
            }

    /** Preconditions, source checks, egress status and late arrivals of every running recording. */
    private suspend fun supervisionLocked() {
        val open = openSessions().associateBy { it.roomId }
        state.retainRooms(open.keys)
        val enabled = PulpitRecordingSettingsStore.currentValue().enabled
        val allowedSpaces = transaction { spacesWithOption(open.values.map { it.spaceId }) }
        // Sessions of rooms that are gone.
        state.sessionIds().filter { it !in open }.forEach { stopSessionLocked(roomId = it, reason = PulpitStopReason.SESSION_CLOSED) }
        for (target in open.values) {
            if (state.hasConfirmations(target.roomId)) {
                val sha = transaction { currentTexts(target.profile).second }
                state.invalidateConfirmationsIfTextChanged(roomId = target.roomId, currentPulpitTextSha256 = sha)
            }
            val session = state.session(target.roomId)
            if (session == null) {
                val pendingActor = state.pendingActor(target.roomId)
                if (pendingActor != null) sendPendingAsk(target) else state.clearConfirmations(target.roomId)
                continue
            }
            val reason =
                when {
                    !enabled -> PulpitStopReason.INSTANCE_DISABLED
                    target.spaceId !in allowedSpaces -> PulpitStopReason.ROOM_OPTION_OFF
                    floorState.current(target.roomId) != null -> PulpitStopReason.FLOOR_GRANTED
                    session.egresses.values.any { it.startedAt.elapsedNow() > recordingConfig.maxDurationMinutes.minutes } ->
                        PulpitStopReason.MAX_DURATION
                    !diskHasRoom() -> PulpitStopReason.DISK_LOW
                    else -> null
                }
            if (reason != null) {
                stopSessionLocked(roomId = target.roomId, reason = reason)
                continue
            }
            superviseSession(target = target, session = session)
        }
    }

    private suspend fun sendPendingAsk(target: PulpitTarget) {
        val roster = runCatching { liveKitAdminClient.listParticipants(target.livekitRoomName) }.getOrNull() ?: return
        val selection =
            transaction {
                PulpitRecordingSources.select(
                    spaceId = target.spaceId,
                    roomId = target.roomId,
                    liveIdentities = roster.map { it.identity }.toSet(),
                    confirmed = state.confirmedSet(target.roomId),
                    requireConfirmation = true,
                )
            }
        // The ask is sent once per arrival, not on every tick: see the roster bookkeeping of a running session; here the packet is cheap and idempotent.
        if (selection.unconfirmed.isNotEmpty()) {
            send(
                livekitRoomName = target.livekitRoomName,
                identities =
                    selection.unconfirmed.map {
                        it.toString()
                    },
                signal = EncounterRecordingSignal(active = false, ask = true),
            )
        }
    }

    private suspend fun superviseSession(
        target: PulpitTarget,
        session: PulpitRecordingState.Session,
    ) {
        dropNonPulpitSourcesLocked(roomId = target.roomId, session = session)
        if (state.session(target.roomId) == null) return
        // Egresses that ended on their own (the person left, LiveKit's own limit): archive the file, keep the session armed.
        val infos = liveKitEgressClient.listEgress(session.livekitRoomName).associateBy { it.egressId }
        session.egresses.values.toList().forEach { egress ->
            val info = infos[egress.egressId]
            if (info != null && info.status in TERMINAL_EGRESS_STATUSES) {
                session.egresses.remove(egress.memberId)
                state.addFinalizing(
                    PulpitRecordingState.Finalizing(
                        spaceId = session.spaceId,
                        livekitRoomName = session.livekitRoomName,
                        egressId = egress.egressId,
                        fileId = egress.fileId,
                        startedAtWall = egress.startedAtWall,
                        uploaderMemberId = session.actorMemberId,
                        stoppedAt = state.nowMark(),
                    ),
                )
            }
        }
        val participants = liveKitAdminClient.listParticipants(session.livekitRoomName)
        // Late arrivals learn the state, point to point; a pulpit person who has not confirmed is asked.
        val toTell = participants.filter { notifiedKey(it) !in session.notified }
        if (toTell.isNotEmpty()) {
            val roster = participants.map { it.identity }.toSet()
            val unconfirmed =
                transaction {
                    PulpitRecordingSources
                        .select(
                            spaceId = target.spaceId,
                            roomId = target.roomId,
                            liveIdentities = roster,
                            confirmed = state.confirmedSet(target.roomId),
                            requireConfirmation = target.requireConfirmation,
                        ).unconfirmed
                        .map { it.toString() }
                        .toSet()
                }
            val asked = toTell.filter { it.identity in unconfirmed }.map { it.identity }
            val plain = toTell.filter { it.identity !in unconfirmed }.map { it.identity }
            if (plain.isNotEmpty()) {
                send(
                    livekitRoomName = session.livekitRoomName,
                    identities = plain,
                    signal = EncounterRecordingSignal(active = true),
                )
            }
            if (asked.isNotEmpty()) {
                send(
                    livekitRoomName = session.livekitRoomName,
                    identities = asked,
                    signal = EncounterRecordingSignal(active = true, ask = true),
                )
            }
            toTell.forEach { session.notified += notifiedKey(it) }
        }
        // A pulpit person who arrived (or came back) after the start is recorded as soon as they have confirmed / none is required.
        fillSourcesLocked(
            target = target,
            session = session,
            participants = participants,
            actorMemberId = session.actorMemberId,
            reverify = {},
        )
    }

    /** Stops the egress of every source that is no ACTIVE PULPIT of the space any more. Inside [mutex]. */
    private suspend fun dropNonPulpitSourcesLocked(
        roomId: Uuid,
        session: PulpitRecordingState.Session,
    ) {
        val offenders =
            transaction {
                session.egresses.values.filter {
                    !PulpitRecordingSources.isStillPulpit(spaceId = session.spaceId, identity = it.memberId.toString())
                }
            }
        if (offenders.isNotEmpty()) {
            stopEgressesLocked(
                roomId = roomId,
                session = session,
                targets = offenders,
                reason = PulpitStopReason.SOURCE_NOT_PULPIT,
                actor = null,
            )
        }
    }

    /** Inside a transaction: the subset of [spaceIds] whose room option is on. */
    private fun spacesWithOption(spaceIds: List<Uuid>): Set<Uuid> =
        if (spaceIds.isEmpty()) {
            emptySet()
        } else {
            EncounterSpaceTable
                .selectAll()
                .where { EncounterSpaceTable.pulpitRecordingAllowed eq true }
                .map { it[EncounterSpaceTable.id] }
                .filter { it in spaceIds }
                .toSet()
        }

    // ── Archiving ────────────────────────────────────────────────────────

    /** Archives the files of finished egresses (or deletes the discarded ones). Files that cannot be handled yet are kept for the next run. */
    private suspend fun finalizeLocked() {
        val queue = state.finalizingSnapshot()
        if (queue.isEmpty()) return
        val byRoom = queue.groupBy { it.livekitRoomName }
        val infos = HashMap<String, Map<String, LiveKitEgressInfo>>()
        byRoom.keys.forEach { roomName ->
            runCatching { liveKitEgressClient.listEgress(roomName) }.onSuccess { list ->
                infos[roomName] = list.associateBy { it.egressId }
            }
        }
        queue.forEach { entry ->
            val known = infos[entry.livekitRoomName] ?: return@forEach // LiveKit unreachable: try again next run
            val info = known[entry.egressId]
            val terminal = info == null || info.status in TERMINAL_EGRESS_STATUSES
            if (!terminal) return@forEach
            val file = hostFile(spaceId = entry.spaceId, fileId = entry.fileId)
            when {
                entry.discard -> {
                    withContext(Dispatchers.IO) { file.delete() }
                    state.replaceFinalizing(old = entry, new = null)
                }
                info != null && info.status != "EGRESS_COMPLETE" -> {
                    logger.warn { "pulpit recording: a file ended without completing (${info.status}) and stays on the volume" }
                    state.replaceFinalizing(old = entry, new = null)
                }
                !file.isFile -> retryOrDrop(entry)
                else -> archiveOne(entry = entry, file = file)
            }
        }
    }

    private suspend fun archiveOne(
        entry: PulpitRecordingState.Finalizing,
        file: File,
    ) {
        // A file ffprobe cannot read is not archived (it may still be written); it is tried again until the attempts run out.
        if (prober !== RecordingMediaProber.None && prober.probeDurationSeconds(file) == null) {
            retryOrDrop(entry)
            return
        }
        try {
            val assembly = transaction { assemblyOf(entry.spaceId) }
            withContext(Dispatchers.IO) {
                PulpitRecordingDocuments.archive(
                    file = file,
                    spaceId = entry.spaceId,
                    fileId = entry.fileId,
                    recordedAt = entry.startedAtWall,
                    uploadedBy = entry.uploaderMemberId,
                    assembly = assembly,
                    root = documentStorageRoot,
                )
                val deleted = file.delete()
                if (!deleted) logger.warn { "pulpit recording: the raw file could not be removed after archiving" }
            }
            state.replaceFinalizing(old = entry, new = null)
            logger.info { "pulpit recording archived" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error { "pulpit recording: archiving failed (${e::class.simpleName})" }
            retryOrDrop(entry)
        }
    }

    private fun retryOrDrop(entry: PulpitRecordingState.Finalizing) {
        if (entry.attempts + 1 >= MAX_FINALIZING_ATTEMPTS) {
            logger.warn { "pulpit recording: a file could not be archived and stays on the volume" }
            state.replaceFinalizing(old = entry, new = null)
        } else {
            state.replaceFinalizing(old = entry, new = entry.copy(attempts = entry.attempts + 1))
        }
    }

    private fun assemblyOf(spaceId: Uuid): Boolean =
        EncounterSpaceTable
            .selectAll()
            .where { EncounterSpaceTable.id eq spaceId }
            .singleOrNull()
            ?.let { profileOf(it) == EncounterProfile.ASSEMBLY } == true

    private fun hostFile(
        spaceId: Uuid,
        fileId: Uuid,
    ): File =
        File(
            recordingConfig.outputHostDir,
        ).resolve(RecordingEgressPaths.PULPIT_DIRECTORY).resolve(spaceId.toString()).resolve("$fileId.mp4")

    /**
     * Once an hour: counts the pulpit files on the egress volume that nobody owns any more (older than the egress timeout and not queued). Nothing is
     * deleted and nothing is archived automatically -- the retention is unlimited by decision, and a file without a known uploader needs a person.
     */
    private suspend fun orphanFileCheckLocked() {
        val now = clock()
        val last = lastOrphanFileCheck
        if (last != null && now.toInstant(ServerClock.zone) - last.toInstant(ServerClock.zone) < 1.hours) return
        lastOrphanFileCheck = now
        val root = File(recordingConfig.outputHostDir).resolve(RecordingEgressPaths.PULPIT_DIRECTORY)
        val queued = state.finalizingSnapshot().map { it.fileId.toString() }.toSet()
        val running =
            state
                .sessionIds()
                .flatMap {
                    state
                        .session(it)
                        ?.egresses
                        ?.values
                        .orEmpty()
                }.map { it.fileId.toString() }
                .toSet()
        val cutoff = System.currentTimeMillis() - recordingConfig.egressTimeoutMinutes.minutes.inWholeMilliseconds
        val count =
            withContext(Dispatchers.IO) {
                root
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "mp4" && it.lastModified() < cutoff }
                    .count { it.nameWithoutExtension !in queued && it.nameWithoutExtension !in running }
            }
        if (count > 0) logger.warn { "pulpit recording: $count unclaimed file(s) on the egress volume (nothing was deleted)" }
    }

    // ── Packets ──────────────────────────────────────────────────────────

    private suspend fun broadcast(
        livekitRoomName: String,
        signal: EncounterRecordingSignal,
    ) {
        try {
            liveKitAdminClient.sendData(
                room = livekitRoomName,
                topic = ENCOUNTER_RECORDING_TOPIC,
                payload = encounterRecordingPayload(signal).toByteArray(Charsets.UTF_8),
            )
        } catch (e: LiveKitAdminException) {
            logger.warn { "pulpit recording: the state could not be sent to the room" }
        }
    }

    private suspend fun send(
        livekitRoomName: String,
        identities: List<String>,
        signal: EncounterRecordingSignal,
    ) {
        if (identities.isEmpty()) return
        try {
            identities.chunked(MAX_SEND_DATA_DESTINATIONS).forEach { chunk ->
                liveKitAdminClient.sendDataTo(
                    room = livekitRoomName,
                    topic = ENCOUNTER_RECORDING_TOPIC,
                    payload = encounterRecordingPayload(signal).toByteArray(Charsets.UTF_8),
                    destinationIdentities = chunk,
                )
            }
        } catch (e: LiveKitAdminException) {
            logger.warn { "pulpit recording: a notice could not be sent" }
        }
    }

    private suspend fun rosterOrUnavailable(livekitRoomName: String): List<LiveKitParticipantInfo> =
        try {
            liveKitAdminClient.listParticipants(livekitRoomName)
        } catch (e: LiveKitAdminException) {
            throw PulpitRecordingUnavailableException()
        }

    /** The hashes of `(REC_ROOM, REC_PULPIT)` for [profile]. Inside a transaction. */
    private fun currentTexts(profile: EncounterProfile): Pair<String, String> =
        AgreementTextResolver.current(kind = AgreementTextKind.REC_ROOM, profile = profile).sha256 to
            AgreementTextResolver.current(kind = AgreementTextKind.REC_PULPIT, profile = profile).sha256

    private fun notifiedKey(p: LiveKitParticipantInfo): String = "${p.identity}|${p.sid}"
}
