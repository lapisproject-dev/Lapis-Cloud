package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceStreamingConfig
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.conference.LiveKitEgressClient
import network.lapis.cloud.server.conference.StreamUrlFingerprint
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.federation.isIpv6UniqueLocalAddress
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ConferenceStreamAvailabilityDto
import network.lapis.cloud.shared.domain.ConferenceStreamDestinationDto
import network.lapis.cloud.shared.domain.ConferenceStreamDto
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetDto
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatusDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IConferenceStreamingService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Non-terminal [ConferenceStreamStatus] values -- "already active" for the one-active-stream-per-room invariant, the cross-room one-active-stream-per-DESTINATION invariant (see [ConferenceStreamingService.startStream]'s `destinationsAlreadyStreaming` check), AND the set [network.lapis.cloud.server.conference.StreamPoller] (and [ConferenceStreamingService.deleteDestination]'s reference guard) treat as "still using this destination". */
private val ACTIVE_STREAM_STATUSES =
    listOf(
        ConferenceStreamStatus.STARTING,
        ConferenceStreamStatus.LIVE,
        ConferenceStreamStatus.PAUSED,
        ConferenceStreamStatus.STOPPING,
    )

/** DoS guard for [ConferenceStreamingService.listStreams] -- same class of cap [ConferenceRecordingService.listRecordings]'s own limit enforces. */
private const val MAX_LIST_RESULTS = 200

/** [ConferenceStreamDestinationDto.streamKeyMask] -- ALWAYS this literal, see that field's own KDoc. */
private const val STREAM_KEY_MASK = "********"

/**
 * Matches a bare IPv4 dotted-decimal literal (e.g. `169.254.169.254`) -- deliberately loose
 * (per-octet range 0-999, not validated as 0-255) since it is only used to DECIDE whether
 * [InetAddress.getByName] should be called on the string at all, not to fully validate it; an
 * out-of-range octet still reaches [InetAddress.getByName], which itself rejects it (see
 * [ConferenceStreamingService.rejectIfUnsafeLiteralHost] KDoc).
 */
private val IPV4_LITERAL_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

/**
 * Fixed, sanitized German vocabulary for a stream-level (not per-target) failure reason produced
 * by THIS class's own synchronous LiveKit calls (`startStream`/`resumeStream`) -- deliberately a
 * single generic string, never the underlying [LiveKitAdminException.message]. Unlike
 * [network.lapis.cloud.server.conference.StreamPoller]'s own per-target vocabulary (which maps raw
 * `stream_results[].error` text that CAN echo a destination host, see that class KDoc),
 * [LiveKitAdminException.message] as thrown by [LiveKitEgressClient]'s HTTP implementation is
 * ALREADY generic by construction (network-error class name / HTTP status / "unparseable body" --
 * never request/response payload content, see `HttpLiveKitEgressClient`'s private `call` helper) --
 * this constant exists purely so the ConferenceStreamDto surface reads the same German vocabulary
 * class as everything else, not because the raw message itself would be unsafe to show.
 */
private const val FAILURE_START_FAILED = "Der Stream konnte nicht gestartet werden."

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 3 "Externes Streaming" -- see
 * [IConferenceStreamingService] KDoc for the full authorization matrix, the three-axis "separate
 * service" reasoning, and the "startStream DOES call LiveKit synchronously" design decision this
 * class implements byte-for-byte. [config]/[streamingConfig] both default to a fresh `.load()` per
 * construction, same "cheap, pure env-var read, safe to repeat" reasoning
 * [ConferenceRecordingService]'s own KDoc gives (this codebase constructs one service instance per
 * RPC call). [liveKitEgressClient] has NO default -- unlike [ConferenceRecordingService] (which
 * never touches LiveKit at all), this class calls it directly for
 * `startStream`/`pauseStream`/`resumeStream`/`stopStream`, so a real instance must always be threaded
 * in from `Application.module` (the SAME shared [LiveKitEgressClient] instance
 * `network.lapis.cloud.server.conference.StreamPoller` also uses).
 *
 * ## `secretBox` -- `null` unless streaming is fully configured
 *
 * [streamingConfig.secretEncryptionKey][ConferenceStreamingConfig.secretEncryptionKey] is `null`
 * whenever `LAPIS_STREAMING_ENABLED` is unset (see that class's own KDoc "Fail-fast") -- in that
 * case [secretBox] is also `null`, and [requireStreamingEnabled] rejects every method that would
 * otherwise need it BEFORE any code path can dereference it. Every call site that DOES reach a
 * `secretBox!!` has therefore already passed [requireStreamingEnabled] on the same request.
 *
 * ## Authorization re-derivation, never a cached role
 *
 * [requireModeratorOrPrivileged] recomputes "is this caller the room's creator, or BOARD/ADMIN"
 * from [ConferenceRoomTable.createdByMemberId] plus [CurrentMember.isPrivileged] on EVERY call --
 * same discipline [ConferenceRecordingService]'s own KDoc documents at length, deliberately
 * duplicated here rather than extracted into a shared helper (see [IConferenceStreamingService]
 * KDoc "Different collaborators").
 *
 * ## `listStreamTargets` has no `roomId` parameter -- how its "room creator OR BOARD/ADMIN" role
 * check is actually implemented
 *
 * [IConferenceStreamingService.listStreamTargets] carries no room context at all (it is a flat
 * destination-picker catalog, not a per-room query) -- so "room creator" cannot be checked against
 * one specific room. This implementation interprets the role predicate as "privileged (BOARD/ADMIN),
 * OR the creator of at least one [ConferenceRoomTable] row" -- a non-privileged caller who has never
 * created a room has no legitimate reason to see the destination catalog at all (they could never
 * reach `startStream` for ANY room), while a caller who created even one room is treated as "a room
 * moderator" for the purpose of this flat catalog read. The precise, per-room check still happens
 * where it actually matters -- [startStream] re-derives "is this caller the creator of THIS room, or
 * privileged" from scratch against the specific room being started.
 *
 * ## `startStream`/`resumeStream` -- the two-transaction, synchronous-LiveKit-call pattern
 *
 * See [IConferenceStreamingService] KDoc "startStream DOES call LiveKit synchronously" for the full
 * rationale. Both methods follow: transaction 1 inserts/updates the row to
 * [ConferenceStreamStatus.STARTING] and (for [startStream]) the `conference_stream_target` rows
 * (status [ConferenceStreamTargetStatus.PENDING], `url_fingerprint` computed via
 * [StreamUrlFingerprint.of] over the PLAINTEXT `<rtmpUrl>/<streamKey>` this class is about to send --
 * see that object's KDoc) -- then, OUTSIDE any transaction, the LiveKit call -- then transaction 2
 * stores the result ([ConferenceStreamStatus.LIVE] + `livekit_egress_id`, or
 * [ConferenceStreamStatus.FAILED] + [FAILURE_START_FAILED] on every target row too). A crash between
 * the two transactions leaves an orphan `STARTING` row -- [network.lapis.cloud.server.conference.StreamPoller]
 * reconciles it by cross-checking `ListEgress`'s `stream_results` against the ALREADY-PERSISTED
 * `url_fingerprint` values (computed in transaction 1, so they survive the crash) rather than
 * leaking a genuinely-started egress.
 *
 * ## `pauseStream`/`resumeStream`/`stopStream` -- best-effort `StopEgress`, never a blocking failure
 *
 * `StopEgress` is called OUTSIDE any transaction and wrapped in its own `try`/`catch` that only
 * WARN-logs on [LiveKitAdminException] -- the state transition (`PAUSED`/`ENDED`) proceeds
 * regardless. This mirrors [network.lapis.cloud.server.conference.RecordingPoller.handleStopping]'s
 * own "StopEgress failed -- log and continue" discipline: a transient LiveKit outage must never
 * strand a moderator's meeting-ending action, and `StopEgress` is itself idempotent to call again
 * (LiveKit simply reports current status) so nothing is lost by not retrying here -- any egress that
 * genuinely kept running despite the failed `StopEgress` call is picked up by
 * [network.lapis.cloud.server.conference.StreamPoller]'s own max-duration/room-ended safety nets on
 * a later tick regardless of this row's own status.
 *
 * ## Rate limiting
 *
 * [destinationRateLimiter] is a [LoginRateLimiter] reused as a generic per-member throttle for
 * EVERY destination-CRUD mutation (create/update/setEnabled/delete) -- "credential writes are rare
 * and brute-force-adjacent" (see the RPC contract doc). [startStreamRateLimiter] is likewise a
 * [LoginRateLimiter] (every attempt counts, successful or not) -- same reuse
 * [ConferenceRecordingService.startRecordingRateLimiter] already establishes for the analogous
 * "rare, moderator-gated, room-creating action" shape. [mutateRateLimiter]/[readRateLimiter] are
 * plain per-member REQUEST-rate limiters reusing [FederationInboxRateLimiter] -- same "many
 * legitimate calls must not each look like a failure" reasoning
 * [ConferenceRecordingService.stopRecordingRateLimiter]/`readRateLimiter` KDoc gives.
 *
 * ### Constructor defaults exist for tests only -- production MUST pass shared instances
 *
 * Because Kilua RPC's `registerService` factory lambda (see [IConferenceStreamingService]'s
 * registration in `Application.module`) constructs a brand-new [ConferenceStreamingService] on
 * EVERY RPC dispatch (this class's own "one service instance per RPC call" note above), the four
 * rate-limiter constructor parameters below MUST be threaded through as explicit, shared
 * `Application.module`-scoped `val`s -- exactly like [ConferenceService]'s own
 * `conferenceRoomRateLimiter`/`conferenceJoinRateLimiter`/`conferenceLeaveRateLimiter`/
 * `conferenceListRateLimiter` already are. A default-argument-only registration (relying on the
 * `= LoginRateLimiter()` / `= FederationInboxRateLimiter(...)` defaults below) would silently
 * construct a fresh, empty-state limiter on every single call, so `checkAllowed`/`checkAndRecord`
 * would never see any prior history and rate limiting would be completely non-functional in
 * production despite this KDoc's own claims above (audit-round-2 finding, Wave 3). The defaults
 * stay on the constructor purely so `ConferenceStreamingServiceTest`'s
 * `registerConferenceStreamingTestRoutes` helper can omit them when a test doesn't care about rate
 * limiting -- they must never be relied upon by `Application.module` itself.
 */
class ConferenceStreamingService(
    private val call: ApplicationCall,
    private val liveKitEgressClient: LiveKitEgressClient,
    private val config: ConferenceConfig = ConferenceConfig.load(),
    private val streamingConfig: ConferenceStreamingConfig = ConferenceStreamingConfig.load(),
    private val destinationRateLimiter: LoginRateLimiter = LoginRateLimiter(),
    private val startStreamRateLimiter: LoginRateLimiter = LoginRateLimiter(),
    private val mutateRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_MUTATE_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
    private val readRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_READ_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
) : IConferenceStreamingService {
    /** See class KDoc "secretBox -- null unless streaming is fully configured". */
    private val secretBox: SecretBox? = streamingConfig.secretEncryptionKey?.let { SecretBox(it) }

    // ── Availability ────────────────────────────────────────────────────

    override suspend fun getStreamingAvailability(): ConferenceStreamAvailabilityDto {
        resolveCurrentMember(call)
        val encryptionConfigured = streamingConfig.secretEncryptionKey != null
        val configuredCount = transaction { ConferenceStreamDestinationTable.selectAll().count() }
        return ConferenceStreamAvailabilityDto(
            enabled = config.enabled && streamingConfig.enabled && encryptionConfigured,
            encryptionConfigured = encryptionConfigured,
            maxDestinations = streamingConfig.maxDestinations,
            configuredDestinationCount = configuredCount.toInt(),
        )
    }

    // ── Destination configuration -- ADMIN ONLY ─────────────────────────

    override suspend fun listDestinations(): List<ConferenceStreamDestinationDto> {
        val current = resolveCurrentMember(call)
        requireStreamingEnabled()
        current.requireRole(AccountRole.ADMIN)
        requireWithinRate(readRateLimiter, current.memberId)
        return transaction {
            ConferenceStreamDestinationTable
                .selectAll()
                .orderBy(ConferenceStreamDestinationTable.createdAt, SortOrder.ASC)
                .map { destinationRowToDto(it) }
        }
    }

    override suspend fun createDestination(
        label: String,
        platform: ConferenceStreamPlatform,
        rtmpUrl: String,
        streamKey: String,
    ): ConferenceStreamDestinationDto {
        val current = resolveCurrentMember(call)
        requireStreamingEnabled()
        current.requireRole(AccountRole.ADMIN)
        requireWithinLoginRate(destinationRateLimiter, current.memberId)

        val trimmedLabel = label.trim()
        if (trimmedLabel.isBlank()) throw BadRequestException("label must not be blank")
        validateRtmpUrl(rtmpUrl)
        if (streamKey.isBlank()) throw BadRequestException("streamKey must not be blank")

        return transaction {
            val exists =
                ConferenceStreamDestinationTable
                    .selectAll()
                    .where { ConferenceStreamDestinationTable.label eq trimmedLabel }
                    .limit(
                        1,
                    ).any()
            if (exists) throw ConflictException("A stream destination named '$trimmedLabel' already exists")

            val now = nowLocalDateTime()
            val newId = Uuid.random()
            // AAD-bound to the destination's OWN id -- see SecretBox KDoc "AAD binding to the
            // owning row". secretBox is non-null here: requireStreamingEnabled() above guarantees it.
            val ciphertext = secretBox!!.seal(streamKey, aad = newId.toString())
            ConferenceStreamDestinationTable.insert {
                it[id] = newId
                it[ConferenceStreamDestinationTable.label] = trimmedLabel
                it[ConferenceStreamDestinationTable.platform] = platform
                it[ConferenceStreamDestinationTable.rtmpUrl] = rtmpUrl.trim()
                it[streamKeyCiphertext] = ciphertext
                it[streamKeySetAt] = now
                it[createdByMemberId] = current.memberId
                it[createdAt] = now
                it[enabled] = true
            }
            // AuditLogRecorder.record must be the LAST lock-taking operation in this transaction --
            // see that object's KDoc "deadlock-avoidance contract".
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.CONFERENCE_STREAM_DESTINATION,
                entityId = newId,
                action = AuditAction.CREATE,
                occurredAt = now,
            )
            val row = ConferenceStreamDestinationTable.selectAll().where { ConferenceStreamDestinationTable.id eq newId }.single()
            destinationRowToDto(row)
        }
    }

    override suspend fun updateDestination(
        destinationId: String,
        label: String,
        rtmpUrl: String,
        newStreamKey: String?,
    ): ConferenceStreamDestinationDto {
        val current = resolveCurrentMember(call)
        requireStreamingEnabled()
        current.requireRole(AccountRole.ADMIN)
        requireWithinLoginRate(destinationRateLimiter, current.memberId)

        val trimmedLabel = label.trim()
        if (trimmedLabel.isBlank()) throw BadRequestException("label must not be blank")
        validateRtmpUrl(rtmpUrl)
        // See IConferenceStreamingService.updateDestination KDoc -- null means "unchanged", a
        // BLANK non-null value is rejected outright, never silently stored.
        if (newStreamKey != null && newStreamKey.isBlank()) {
            throw BadRequestException("newStreamKey must not be blank -- omit it entirely to leave the stored key unchanged")
        }

        val destUuid = destinationId.toStreamUuid()
        return transaction {
            ConferenceStreamDestinationTable.selectAll().where { ConferenceStreamDestinationTable.id eq destUuid }.singleOrNull()
                ?: throw NotFoundException("Conference stream destination $destUuid not found")
            val labelConflict =
                ConferenceStreamDestinationTable
                    .selectAll()
                    .where {
                        (ConferenceStreamDestinationTable.label eq trimmedLabel) and (ConferenceStreamDestinationTable.id neq destUuid)
                    }.limit(1)
                    .any()
            if (labelConflict) throw ConflictException("A stream destination named '$trimmedLabel' already exists")

            val now = nowLocalDateTime()
            ConferenceStreamDestinationTable.update({ ConferenceStreamDestinationTable.id eq destUuid }) {
                it[ConferenceStreamDestinationTable.label] = trimmedLabel
                it[ConferenceStreamDestinationTable.rtmpUrl] = rtmpUrl.trim()
                if (newStreamKey != null) {
                    it[streamKeyCiphertext] = secretBox!!.seal(newStreamKey, aad = destUuid.toString())
                    it[streamKeySetAt] = now
                }
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.CONFERENCE_STREAM_DESTINATION,
                entityId = destUuid,
                action = AuditAction.UPDATE,
                occurredAt = now,
            )
            val row = ConferenceStreamDestinationTable.selectAll().where { ConferenceStreamDestinationTable.id eq destUuid }.single()
            destinationRowToDto(row)
        }
    }

    override suspend fun setDestinationEnabled(
        destinationId: String,
        enabled: Boolean,
    ): ConferenceStreamDestinationDto {
        val current = resolveCurrentMember(call)
        requireStreamingEnabled()
        current.requireRole(AccountRole.ADMIN)
        requireWithinLoginRate(destinationRateLimiter, current.memberId)
        val destUuid = destinationId.toStreamUuid()
        return transaction {
            ConferenceStreamDestinationTable.selectAll().where { ConferenceStreamDestinationTable.id eq destUuid }.singleOrNull()
                ?: throw NotFoundException("Conference stream destination $destUuid not found")
            val now = nowLocalDateTime()
            ConferenceStreamDestinationTable.update({ ConferenceStreamDestinationTable.id eq destUuid }) {
                it[ConferenceStreamDestinationTable.enabled] = enabled
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.CONFERENCE_STREAM_DESTINATION,
                entityId = destUuid,
                action = AuditAction.UPDATE,
                occurredAt = now,
            )
            val row = ConferenceStreamDestinationTable.selectAll().where { ConferenceStreamDestinationTable.id eq destUuid }.single()
            destinationRowToDto(row)
        }
    }

    override suspend fun deleteDestination(destinationId: String): Boolean {
        val current = resolveCurrentMember(call)
        requireStreamingEnabled()
        current.requireRole(AccountRole.ADMIN)
        requireWithinLoginRate(destinationRateLimiter, current.memberId)
        val destUuid = destinationId.toStreamUuid()
        return transaction {
            val row =
                ConferenceStreamDestinationTable.selectAll().where { ConferenceStreamDestinationTable.id eq destUuid }.singleOrNull()
                    ?: throw NotFoundException("Conference stream destination $destUuid not found")

            // Explicit, nicely-worded guard for the common case -- see IConferenceStreamingService
            // .deleteDestination KDoc.
            val activeReference =
                (ConferenceStreamTargetTable innerJoin ConferenceStreamTable)
                    .selectAll()
                    .where {
                        (ConferenceStreamTargetTable.destinationId eq destUuid) and
                            (ConferenceStreamTable.status inList ACTIVE_STREAM_STATUSES)
                    }.limit(1)
                    .any()
            if (activeReference) {
                throw ConflictException("Stream destination $destUuid is referenced by an active (or starting/stopping) stream")
            }

            // Backstop: conference_stream_target.destination_id is a real FK with NO cascade (see
            // V1__baseline.sql) -- a destination that was ever used by a now-ENDED/FAILED stream
            // still has historical target rows referencing it, and the raw DELETE below would throw
            // a DB-level FK violation for that case. Translated into the same ConflictException
            // shape as the explicit check above rather than leaking a raw SQL exception to the
            // client -- same "pre-check + backstop" idiom PoliticianService already establishes.
            try {
                ConferenceStreamDestinationTable.deleteWhere { ConferenceStreamDestinationTable.id eq destUuid }
            } catch (e: ExposedSQLException) {
                throw ConflictException("Stream destination $destUuid cannot be deleted -- it is referenced by historical stream records")
            }

            val now = nowLocalDateTime()
            // AuditAction has no DELETE literal (see network.lapis.cloud.shared.domain.AuditAction
            // KDoc) -- recorded as UPDATE with the deleted row's own label as the "before" snapshot,
            // same "closest available vocabulary" posture this codebase already accepts elsewhere.
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.CONFERENCE_STREAM_DESTINATION,
                entityId = destUuid,
                action = AuditAction.UPDATE,
                before = row[ConferenceStreamDestinationTable.label],
                occurredAt = now,
            )
            true
        }
    }

    // ── Moderator-facing target picker ──────────────────────────────────

    override suspend fun listStreamTargets(): List<ConferenceStreamTargetDto> {
        val current = resolveCurrentMember(call)
        requireStreamingEnabled()
        requireWithinRate(readRateLimiter, current.memberId)
        return transaction {
            requireActiveMembership(current.memberId)
            // See class KDoc "listStreamTargets has no roomId parameter".
            if (!current.isPrivileged) {
                val ownsAnyRoom =
                    ConferenceRoomTable
                        .selectAll()
                        .where { ConferenceRoomTable.createdByMemberId eq current.memberId }
                        .limit(
                            1,
                        ).any()
                if (!ownsAnyRoom) throw ForbiddenException()
            }
            ConferenceStreamDestinationTable
                .selectAll()
                .where { ConferenceStreamDestinationTable.enabled eq true }
                .orderBy(ConferenceStreamDestinationTable.label, SortOrder.ASC)
                .map { row ->
                    ConferenceStreamTargetDto(
                        id = row[ConferenceStreamDestinationTable.id].toString(),
                        label = row[ConferenceStreamDestinationTable.label],
                        platform = row[ConferenceStreamDestinationTable.platform],
                    )
                }
        }
    }

    // ── Stream lifecycle ─────────────────────────────────────────────────

    override suspend fun startStream(
        roomId: String,
        destinationIds: List<String>,
        layout: ConferenceStreamLayout,
        latencyMode: ConferenceStreamLatencyMode,
        participantIdentity: String?,
    ): ConferenceStreamDto {
        val current = resolveCurrentMember(call)
        requireStreamingEnabled()
        val throttleKey = "member:${current.memberId}"
        if (!startStreamRateLimiter.checkAllowed(throttleKey)) {
            throw ConflictException("Too many stream-start attempts -- try again later")
        }
        // Every attempt (successful or not) counts against the throttle -- same reuse
        // ConferenceRecordingService.startRecording's own throttle already establishes.
        startStreamRateLimiter.recordFailure(throttleKey)

        if (layout == ConferenceStreamLayout.SINGLE_PARTICIPANT && participantIdentity.isNullOrBlank()) {
            throw ConflictException("layout=SINGLE_PARTICIPANT requires a non-blank participantIdentity")
        }
        val distinctDestinationIds = destinationIds.distinct()
        if (distinctDestinationIds.size != destinationIds.size) {
            throw ConflictException("destinationIds must not contain duplicates")
        }
        if (distinctDestinationIds.isEmpty()) {
            throw ConflictException("At least one destination is required")
        }
        if (distinctDestinationIds.size > streamingConfig.maxDestinations) {
            throw ConflictException("At most ${streamingConfig.maxDestinations} destinations are allowed per stream")
        }
        val distinctDestinationUuids = distinctDestinationIds.map { it.toStreamUuid() }
        val roomUuid = roomId.toStreamUuid()

        val prep =
            transaction {
                // .forUpdate() locks the room row for the rest of this transaction -- closes the
                // check-then-act race on the "one active stream per room" invariant below, same fix
                // ConferenceRecordingService.startRecording's own KDoc documents for recording.
                val room =
                    ConferenceRoomTable
                        .selectAll()
                        .where { ConferenceRoomTable.id eq roomUuid }
                        .forUpdate()
                        .singleOrNull()
                        ?: throw NotFoundException("Conference room $roomUuid not found")
                if (room[ConferenceRoomTable.endedAt] != null) {
                    throw ConflictException("Conference room $roomUuid has already ended -- cannot start a stream")
                }
                requireModeratorOrPrivileged(room, current)

                val alreadyActive =
                    ConferenceStreamTable
                        .selectAll()
                        .where {
                            (ConferenceStreamTable.roomId eq roomUuid) and (ConferenceStreamTable.status inList ACTIVE_STREAM_STATUSES)
                        }.limit(1)
                        .any()
                if (alreadyActive) {
                    throw ConflictException("A stream is already active for this room")
                }

                // .forUpdate() locks these destination rows for the rest of this transaction --
                // closes the check-then-act race on the "no destination targeted by two
                // simultaneously-active streams" invariant below (see that check's own comment),
                // the exact same mechanism the room-row .forUpdate() above uses for the
                // one-active-stream-per-room invariant.
                val destinationRows =
                    ConferenceStreamDestinationTable
                        .selectAll()
                        .where { ConferenceStreamDestinationTable.id inList distinctDestinationUuids }
                        .forUpdate()
                        .associateBy { it[ConferenceStreamDestinationTable.id] }
                if (destinationRows.size != distinctDestinationUuids.size) {
                    throw ConflictException("One or more destinations do not exist")
                }

                // Cross-ROOM destination exclusivity: listStreamTargets exposes every enabled
                // destination to any room creator, not just the ADMIN who configured it, so two
                // independent moderators in two independent rooms could otherwise each start a
                // stream to the SAME destination concurrently -- two simultaneous RTMP connections
                // to the same external ingest URL/stream key. The "one active stream per ROOM" check
                // above does not catch this (different rooms, different stream rows). Race-closed by
                // the destinationRows .forUpdate() lock just above: a concurrent startStream for a
                // DIFFERENT room targeting an overlapping destination blocks on that lock until this
                // transaction commits (or rolls back), so the read below always reflects every
                // already-committed active/starting/paused/stopping target for these destinations.
                val destinationsAlreadyStreaming =
                    (ConferenceStreamTargetTable innerJoin ConferenceStreamTable)
                        .selectAll()
                        .where {
                            (ConferenceStreamTargetTable.destinationId inList distinctDestinationUuids) and
                                (ConferenceStreamTable.status inList ACTIVE_STREAM_STATUSES)
                        }.map { it[ConferenceStreamTargetTable.destinationId] }
                        .toSet()

                val preparedTargets =
                    distinctDestinationUuids.map { destId ->
                        val destRow = destinationRows.getValue(destId)
                        if (!destRow[ConferenceStreamDestinationTable.enabled]) {
                            throw ConflictException("Destination $destId is disabled")
                        }
                        if (destId in destinationsAlreadyStreaming) {
                            throw ConflictException(
                                "Destination $destId is already targeted by another active stream (in a different room)",
                            )
                        }
                        val plaintextUrl = buildRtmpUrl(destRow)
                        PreparedTarget(destId, plaintextUrl, StreamUrlFingerprint.of(plaintextUrl))
                    }

                val now = nowLocalDateTime()
                val newStreamId = Uuid.random()
                ConferenceStreamTable.insert {
                    it[id] = newStreamId
                    it[ConferenceStreamTable.roomId] = roomUuid
                    it[startedByMemberId] = current.memberId
                    it[status] = ConferenceStreamStatus.STARTING
                    it[ConferenceStreamTable.layout] = layout
                    it[ConferenceStreamTable.latencyMode] = latencyMode
                    it[ConferenceStreamTable.participantIdentity] = participantIdentity
                    it[livekitEgressId] = null
                    it[startedAt] = now
                    it[pausedAt] = null
                    it[endedAt] = null
                    it[restartCount] = 0
                    it[failureReason] = null
                }
                preparedTargets.forEach { pt ->
                    ConferenceStreamTargetTable.insert {
                        it[id] = Uuid.random()
                        it[streamId] = newStreamId
                        it[destinationId] = pt.destinationId
                        it[status] = ConferenceStreamTargetStatus.PENDING
                        it[urlFingerprint] = pt.fingerprint
                        it[startedAtEpochNanos] = null
                        it[endedAtEpochNanos] = null
                        it[retries] = 0
                        it[failureReason] = null
                    }
                }
                // AuditLogRecorder.record must be the LAST lock-taking operation -- see that
                // object's KDoc "deadlock-avoidance contract".
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.CONFERENCE_STREAM,
                    entityId = newStreamId,
                    action = AuditAction.CREATE,
                    occurredAt = now,
                )
                StartPreparation(newStreamId, room[ConferenceRoomTable.livekitRoomName], room[ConferenceRoomTable.title], preparedTargets)
            }

        // OUTSIDE the transaction -- never a network call inside an open one. See
        // IConferenceStreamingService KDoc "startStream DOES call LiveKit synchronously".
        val egressResult =
            runCatching {
                val urls = prep.targets.map { it.plaintextUrl }
                if (layout == ConferenceStreamLayout.SINGLE_PARTICIPANT) {
                    liveKitEgressClient.startParticipantEgress(prep.roomName, participantIdentity!!, latencyMode, urls)
                } else {
                    liveKitEgressClient.startRoomCompositeEgress(prep.roomName, layout, latencyMode, urls)
                }
            }

        return transaction {
            val now = nowLocalDateTime()
            egressResult
                .onSuccess { info ->
                    ConferenceStreamTable.update({ ConferenceStreamTable.id eq prep.streamId }) {
                        it[livekitEgressId] = info.egressId
                        it[status] = ConferenceStreamStatus.LIVE
                    }
                }.onFailure { e ->
                    logger.warn {
                        "startStream: LiveKit egress start failed for stream ${prep.streamId}: ${(e as? LiveKitAdminException)?.message}"
                    }
                    ConferenceStreamTable.update({ ConferenceStreamTable.id eq prep.streamId }) {
                        it[status] = ConferenceStreamStatus.FAILED
                        it[failureReason] = FAILURE_START_FAILED
                    }
                    ConferenceStreamTargetTable.update({ ConferenceStreamTargetTable.streamId eq prep.streamId }) {
                        it[status] = ConferenceStreamTargetStatus.FAILED
                        it[failureReason] = FAILURE_START_FAILED
                    }
                }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.CONFERENCE_STREAM,
                entityId = prep.streamId,
                action = AuditAction.UPDATE,
                occurredAt = now,
            )
            val row = ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq prep.streamId }.single()
            streamRowToDto(row, prep.roomTitle)
        }
    }

    override suspend fun pauseStream(streamId: String): ConferenceStreamDto {
        val current = resolveCurrentMember(call)
        requireStreamingEnabled()
        requireWithinRate(mutateRateLimiter, current.memberId)
        val streamUuid = streamId.toStreamUuid()

        val prep =
            transaction {
                val row =
                    ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.singleOrNull()
                        ?: throw NotFoundException("Conference stream $streamUuid not found")
                val room =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq row[ConferenceStreamTable.roomId] }.singleOrNull()
                        ?: throw NotFoundException("Conference room for stream $streamUuid not found")
                requireModeratorOrPrivileged(room, current)

                // Idempotent -- see IConferenceStreamingService.pauseStream KDoc. Only a LIVE
                // stream has an active egress worth stopping.
                if (row[ConferenceStreamTable.status] != ConferenceStreamStatus.LIVE) {
                    return@transaction PausePrep(room[ConferenceRoomTable.title], null, null, alreadyHandled = true)
                }
                PausePrep(
                    room[ConferenceRoomTable.title],
                    room[ConferenceRoomTable.livekitRoomName],
                    row[ConferenceStreamTable.livekitEgressId],
                    alreadyHandled = false,
                )
            }
        if (prep.alreadyHandled) {
            return transaction {
                val row = ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.single()
                streamRowToDto(row, prep.roomTitle)
            }
        }

        // Best-effort, OUTSIDE any transaction -- see class KDoc "pauseStream/resumeStream/
        // stopStream -- best-effort StopEgress".
        if (prep.egressId != null) {
            try {
                liveKitEgressClient.stopEgress(prep.roomName!!, prep.egressId)
            } catch (e: LiveKitAdminException) {
                logger.warn { "pauseStream: StopEgress failed for stream $streamUuid: ${e.message}" }
            }
        }

        return transaction {
            val now = nowLocalDateTime()
            ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamUuid }) {
                it[status] = ConferenceStreamStatus.PAUSED
                it[pausedAt] = now
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.CONFERENCE_STREAM,
                entityId = streamUuid,
                action = AuditAction.UPDATE,
                occurredAt = now,
            )
            val row = ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.single()
            streamRowToDto(row, prep.roomTitle)
        }
    }

    override suspend fun resumeStream(streamId: String): ConferenceStreamDto {
        val current = resolveCurrentMember(call)
        requireStreamingEnabled()
        requireWithinRate(mutateRateLimiter, current.memberId)
        val streamUuid = streamId.toStreamUuid()

        val prep =
            transaction {
                val row =
                    ConferenceStreamTable
                        .selectAll()
                        .where { ConferenceStreamTable.id eq streamUuid }
                        .forUpdate()
                        .singleOrNull()
                        ?: throw NotFoundException("Conference stream $streamUuid not found")
                val room =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq row[ConferenceStreamTable.roomId] }.singleOrNull()
                        ?: throw NotFoundException("Conference room for stream $streamUuid not found")
                requireModeratorOrPrivileged(room, current)
                if (row[ConferenceStreamTable.status] != ConferenceStreamStatus.PAUSED) {
                    throw ConflictException("Conference stream $streamUuid is not paused")
                }

                // Re-derive the plaintext URLs/fingerprints fresh (destination row still exists --
                // deleteDestination refuses while a PAUSED stream references it, see that method's
                // own KDoc) rather than trusting the target rows' stale fingerprint from the
                // ORIGINAL start -- defensively correct even though the underlying rtmpUrl/key pair
                // cannot actually have changed for an already-approved destination.
                val targetRows =
                    (ConferenceStreamTargetTable innerJoin ConferenceStreamDestinationTable)
                        .selectAll()
                        .where { ConferenceStreamTargetTable.streamId eq streamUuid }
                        .toList()
                val urls =
                    targetRows.map { tr ->
                        val plaintextUrl = buildRtmpUrl(tr)
                        val fingerprint = StreamUrlFingerprint.of(plaintextUrl)
                        ConferenceStreamTargetTable.update({ ConferenceStreamTargetTable.id eq tr[ConferenceStreamTargetTable.id] }) {
                            it[urlFingerprint] = fingerprint
                            it[status] = ConferenceStreamTargetStatus.PENDING
                            it[startedAtEpochNanos] = null
                            it[endedAtEpochNanos] = null
                            it[retries] = 0
                            it[failureReason] = null
                        }
                        plaintextUrl
                    }
                ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamUuid }) { it[status] = ConferenceStreamStatus.STARTING }

                ResumePreparation(
                    room[ConferenceRoomTable.livekitRoomName],
                    room[ConferenceRoomTable.title],
                    row[ConferenceStreamTable.layout],
                    row[ConferenceStreamTable.latencyMode],
                    row[ConferenceStreamTable.participantIdentity],
                    row[ConferenceStreamTable.restartCount],
                    urls,
                )
            }

        // OUTSIDE the transaction -- see startStream's own comment.
        val egressResult =
            runCatching {
                if (prep.layout == ConferenceStreamLayout.SINGLE_PARTICIPANT) {
                    liveKitEgressClient.startParticipantEgress(prep.roomName, prep.participantIdentity!!, prep.latencyMode, prep.urls)
                } else {
                    liveKitEgressClient.startRoomCompositeEgress(prep.roomName, prep.layout, prep.latencyMode, prep.urls)
                }
            }

        return transaction {
            val now = nowLocalDateTime()
            egressResult
                .onSuccess { info ->
                    ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamUuid }) {
                        it[livekitEgressId] = info.egressId
                        it[status] = ConferenceStreamStatus.LIVE
                        it[pausedAt] = null
                        it[restartCount] = prep.previousRestartCount + 1
                    }
                }.onFailure { e ->
                    logger.warn {
                        "resumeStream: LiveKit egress start failed for stream $streamUuid: ${(e as? LiveKitAdminException)?.message}"
                    }
                    ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamUuid }) {
                        it[status] = ConferenceStreamStatus.FAILED
                        it[failureReason] = FAILURE_START_FAILED
                        it[restartCount] = prep.previousRestartCount + 1
                    }
                    ConferenceStreamTargetTable.update({ ConferenceStreamTargetTable.streamId eq streamUuid }) {
                        it[status] = ConferenceStreamTargetStatus.FAILED
                        it[failureReason] = FAILURE_START_FAILED
                    }
                }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.CONFERENCE_STREAM,
                entityId = streamUuid,
                action = AuditAction.UPDATE,
                occurredAt = now,
            )
            val row = ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.single()
            streamRowToDto(row, prep.roomTitle)
        }
    }

    override suspend fun stopStream(streamId: String): ConferenceStreamDto {
        val current = resolveCurrentMember(call)
        requireStreamingEnabled()
        requireWithinRate(mutateRateLimiter, current.memberId)
        val streamUuid = streamId.toStreamUuid()

        val prep =
            transaction {
                val row =
                    ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.singleOrNull()
                        ?: throw NotFoundException("Conference stream $streamUuid not found")
                val room =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq row[ConferenceStreamTable.roomId] }.singleOrNull()
                        ?: throw NotFoundException("Conference room for stream $streamUuid not found")
                requireModeratorOrPrivileged(room, current)

                val status = row[ConferenceStreamTable.status]
                // Idempotent once already ENDED/FAILED -- see IConferenceStreamingService
                // .stopStream KDoc.
                if (status == ConferenceStreamStatus.ENDED || status == ConferenceStreamStatus.FAILED) {
                    return@transaction StopPrep(room[ConferenceRoomTable.title], null, null, alreadyTerminal = true)
                }
                ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamUuid }) {
                    it[ConferenceStreamTable.status] =
                        ConferenceStreamStatus.STOPPING
                }
                StopPrep(
                    room[ConferenceRoomTable.title],
                    room[ConferenceRoomTable.livekitRoomName],
                    row[ConferenceStreamTable.livekitEgressId].takeIf { status == ConferenceStreamStatus.LIVE },
                    alreadyTerminal = false,
                )
            }
        if (prep.alreadyTerminal) {
            return transaction {
                val row = ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.single()
                streamRowToDto(row, prep.roomTitle)
            }
        }

        // Best-effort, OUTSIDE any transaction -- see class KDoc.
        if (prep.roomName != null && prep.egressId != null) {
            try {
                liveKitEgressClient.stopEgress(prep.roomName, prep.egressId)
            } catch (e: LiveKitAdminException) {
                logger.warn { "stopStream: StopEgress failed for stream $streamUuid: ${e.message}" }
            }
        }

        return transaction {
            val now = nowLocalDateTime()
            ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamUuid }) {
                it[status] = ConferenceStreamStatus.ENDED
                it[endedAt] = now
            }
            ConferenceStreamTargetTable.update({
                (ConferenceStreamTargetTable.streamId eq streamUuid) and
                    (
                        ConferenceStreamTargetTable.status inList
                            listOf(ConferenceStreamTargetStatus.PENDING, ConferenceStreamTargetStatus.ACTIVE)
                    )
            }) {
                it[status] = ConferenceStreamTargetStatus.FINISHED
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.CONFERENCE_STREAM,
                entityId = streamUuid,
                action = AuditAction.UPDATE,
                occurredAt = now,
            )
            val row = ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.single()
            streamRowToDto(row, prep.roomTitle)
        }
    }

    // ── Transparency read ────────────────────────────────────────────────

    override suspend fun getActiveStream(roomId: String): List<ConferenceStreamDto> {
        val current = resolveCurrentMember(call)
        requireStreamingEnabled()
        requireWithinRate(readRateLimiter, current.memberId)
        val roomUuid = roomId.toStreamUuid()
        return transaction {
            val room =
                ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomUuid }.singleOrNull()
                    ?: throw NotFoundException("Conference room $roomUuid not found")
            // Wave 5 "Föderations-Gastbeitritt", design review D13 -- widened from
            // requireActiveMembership to the shared conference-domain gate so a federated GUEST
            // who is actually in the room (allowFederationGuests + has joined) can see the stream
            // badge too -- "everyone in the room has a legal right to know" applies to a guest
            // exactly as much as to an AKTIV member. See requireRoomEntryAuthorization KDoc.
            val status = requireRoomEntryAuthorization(room, current)
            requireGuestHasJoinedRoom(roomUuid, current, status)
            val row =
                ConferenceStreamTable
                    .selectAll()
                    .where { (ConferenceStreamTable.roomId eq roomUuid) and (ConferenceStreamTable.status inList ACTIVE_STREAM_STATUSES) }
                    .orderBy(ConferenceStreamTable.startedAt, SortOrder.DESC)
                    .limit(1)
                    .singleOrNull()
                    ?: return@transaction emptyList()
            // Never gated on isPrivileged -- see IConferenceStreamingService.getActiveStream KDoc
            // "everyone in the room has a legal right to know".
            listOf(streamRowToDto(row, room[ConferenceRoomTable.title]))
        }
    }

    override suspend fun listStreams(roomId: String?): List<ConferenceStreamDto> {
        val current = resolveCurrentMember(call)
        requireStreamingEnabled()
        requireWithinRate(readRateLimiter, current.memberId)
        val parsedRoomId = roomId?.toStreamUuid()
        return transaction {
            if (parsedRoomId != null) {
                val room =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq parsedRoomId }.singleOrNull()
                        ?: throw NotFoundException("Conference room $parsedRoomId not found")
                requireModeratorOrPrivileged(room, current)
            } else {
                // No single room to check "creator" against when listing across ALL rooms -- only a
                // global BOARD/ADMIN may do that, see IConferenceStreamingService.listStreams KDoc.
                if (!current.isPrivileged) throw ForbiddenException()
            }
            val query =
                if (parsedRoomId != null) {
                    ConferenceStreamTable.selectAll().where { ConferenceStreamTable.roomId eq parsedRoomId }
                } else {
                    ConferenceStreamTable.selectAll()
                }
            val roomTitleById = mutableMapOf<Uuid, String>()
            query
                .orderBy(ConferenceStreamTable.startedAt, SortOrder.DESC)
                .limit(MAX_LIST_RESULTS)
                .map { row ->
                    val rid = row[ConferenceStreamTable.roomId]
                    val title =
                        roomTitleById.getOrPut(rid) {
                            ConferenceRoomTable
                                .select(ConferenceRoomTable.title)
                                .where { ConferenceRoomTable.id eq rid }
                                .singleOrNull()
                                ?.get(ConferenceRoomTable.title)
                                ?: ""
                        }
                    streamRowToDto(row, title)
                }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /** See [IConferenceStreamingService] KDoc "A third independent availability gate". */
    private fun requireStreamingEnabled() {
        if (!config.enabled) {
            throw ConflictException(
                "Videokonferenzen is not configured on this server (LAPIS_LIVEKIT_URL/_API_KEY/_API_SECRET " +
                    "unset) -- see ConferenceConfig KDoc",
            )
        }
        if (!streamingConfig.enabled) {
            throw ConflictException(
                "Externes Streaming is not enabled on this server (LAPIS_STREAMING_ENABLED unset) -- see " +
                    "ConferenceStreamingConfig KDoc",
            )
        }
        if (secretBox == null) {
            throw ConflictException(
                "Externes Streaming is unavailable on this server (LAPIS_SECRET_ENCRYPTION_KEY missing/invalid) " +
                    "-- see ConferenceStreamingConfig KDoc",
            )
        }
    }

    private fun requireWithinRate(
        limiter: FederationInboxRateLimiter,
        memberId: Uuid,
    ) {
        if (!limiter.checkAndRecord("member:$memberId")) {
            throw ConflictException("Too many requests -- try again later")
        }
    }

    private fun requireWithinLoginRate(
        limiter: LoginRateLimiter,
        memberId: Uuid,
    ) {
        val key = "member:$memberId"
        if (!limiter.checkAllowed(key)) {
            throw ConflictException("Too many requests -- try again later")
        }
        limiter.recordFailure(key)
    }

    /** See class KDoc "Authorization re-derivation, never a cached role". */
    private fun requireModeratorOrPrivileged(
        room: ResultRow,
        current: CurrentMember,
    ) {
        val isCreator = room[ConferenceRoomTable.createdByMemberId] == current.memberId
        if (!isCreator && !current.isPrivileged) throw ForbiddenException()
    }

    private fun validateRtmpUrl(url: String) {
        val uri =
            try {
                URI(url.trim())
            } catch (e: URISyntaxException) {
                throw BadRequestException("rtmpUrl is not a valid URL")
            }
        // Deliberately NOT run through this codebase's FULL SSRF private-range/loopback blocklist
        // (the DNS-resolving, IP-pinning one FederationHttpClient.requireSafeFederationUrl uses) --
        // see IConferenceStreamingService.createDestination KDoc and the Wave 3 scope-decisions doc
        // for the full rationale (rtmpUrl is ADMIN-supplied operator configuration, not
        // externally-influenced user input, and the on-prem-Owncast use case needs RFC1918 to stay
        // reachable). What IS enforced below is a narrower, DNS-free literal-address hard block --
        // see rejectIfUnsafeLiteralHost KDoc.
        if (uri.scheme?.lowercase() !in setOf("rtmp", "rtmps")) {
            throw BadRequestException("rtmpUrl must use the rtmp:// or rtmps:// scheme")
        }
        val host = uri.host
        if (host.isNullOrBlank()) {
            throw BadRequestException("rtmpUrl must have a valid host")
        }
        rejectIfUnsafeLiteralHost(host)
    }

    /**
     * Compensates for [validateRtmpUrl] deliberately NOT DNS-resolving [host] (see that method's
     * KDoc): an ADMIN account -- not equivalent to full infrastructure-operator trust, and a
     * realistic credential-compromise/CSRF/social-engineering target -- must not be able to point
     * the LiveKit egress container's outbound RTMP connection at loopback or a link-local/cloud-
     * metadata address (`169.254.0.0/16`, e.g. the `169.254.169.254` metadata endpoint) merely by
     * pasting a literal IP into `rtmpUrl`. This check is intentionally DNS-free -- it only inspects
     * [host] when it is ALREADY a literal IPv4/IPv6 address (or the `localhost` name), so
     * [InetAddress.getByName] performs zero network I/O and every existing hostname-based
     * `rtmpUrl` (the documented on-prem-Owncast case, and every hostname used by this class's own
     * tests) is completely unaffected -- no new network dependency at admin-config time, no risk of
     * this validation itself hanging/timing out on an unresolvable operator hostname. RFC1918
     * (site-local) addresses are DELIBERATELY still allowed, both as literals and (unchecked, as
     * always) via hostname -- see [validateRtmpUrl] KDoc "the on-prem-Owncast use case". This does
     * NOT close every SSRF angle (a hostname that resolves to a private address at connect time is
     * still possible, same as before) -- see class-level finding notes / the Wave 3 audit round 1
     * writeup for why full DNS-based pinning (as `FederationHttpClient` does for ITS
     * externally-influenced targets) is not applied here: the actual RTMP connection is opened by
     * the out-of-process LiveKit egress container, not by this JVM, so there is no local HTTP client
     * to pin in the first place -- network-level egress segmentation remains the actual control for
     * the residual DNS-based angle.
     */
    private fun rejectIfUnsafeLiteralHost(host: String) {
        if (host.equals("localhost", ignoreCase = true)) {
            throw BadRequestException("rtmpUrl must not target localhost")
        }
        val literal =
            when {
                IPV4_LITERAL_REGEX.matches(host) -> host
                host.startsWith("[") && host.endsWith("]") -> host.substring(1, host.length - 1)
                else -> null
            } ?: return // Not a literal IP address -- a hostname, left unresolved, see KDoc above.
        val addr =
            runCatching { InetAddress.getByName(literal) }.getOrNull()
                // Malformed literal (e.g. an out-of-range IPv4 octet) -- let it through, unchanged
                // from before this fix; the actual connect attempt (by the LiveKit egress process,
                // not this JVM) simply fails on it.
                ?: return
        val unsafe =
            addr.isLoopbackAddress ||
                addr.isLinkLocalAddress ||
                addr.isMulticastAddress ||
                addr.isAnyLocalAddress ||
                isIpv6UniqueLocalAddress(addr)
        if (unsafe) {
            throw BadRequestException(
                "rtmpUrl must not target a loopback, link-local/metadata, multicast, or IPv6 " +
                    "unique-local address ($host) -- RFC1918 private addresses remain allowed for " +
                    "on-prem deployments",
            )
        }
    }

    /**
     * Concatenates a destination's stored `rtmpUrl` with its DECRYPTED stream key -- the ONE place
     * in this class the plaintext key exists in memory, and only for the duration of building this
     * string. Never logged, never persisted, never returned -- see [SecretBox] KDoc and
     * [ConferenceStreamDestinationDto] KDoc. Works identically whether [row] came from a plain
     * [ConferenceStreamDestinationTable] select ([startStream]) or an
     * `ConferenceStreamTargetTable innerJoin ConferenceStreamDestinationTable` join ([resumeStream])
     * -- [ResultRow] column lookup is unaffected by which tables were joined to produce it.
     */
    private fun buildRtmpUrl(row: ResultRow): String {
        val base = row[ConferenceStreamDestinationTable.rtmpUrl].trimEnd('/')
        val destinationId = row[ConferenceStreamDestinationTable.id]
        val key = secretBox!!.open(row[ConferenceStreamDestinationTable.streamKeyCiphertext], aad = destinationId.toString())
        return "$base/$key"
    }

    private fun destinationRowToDto(row: ResultRow): ConferenceStreamDestinationDto =
        ConferenceStreamDestinationDto(
            id = row[ConferenceStreamDestinationTable.id].toString(),
            label = row[ConferenceStreamDestinationTable.label],
            platform = row[ConferenceStreamDestinationTable.platform],
            rtmpUrl = row[ConferenceStreamDestinationTable.rtmpUrl],
            streamKeyMask = STREAM_KEY_MASK,
            streamKeySetAt = row[ConferenceStreamDestinationTable.streamKeySetAt],
            createdByDisplayName = memberDisplayName(row[ConferenceStreamDestinationTable.createdByMemberId]),
            enabled = row[ConferenceStreamDestinationTable.enabled],
        )

    private fun targetDtos(streamId: Uuid): List<ConferenceStreamTargetStatusDto> =
        (ConferenceStreamTargetTable innerJoin ConferenceStreamDestinationTable)
            .selectAll()
            .where { ConferenceStreamTargetTable.streamId eq streamId }
            .map { row ->
                ConferenceStreamTargetStatusDto(
                    destinationId = row[ConferenceStreamTargetTable.destinationId].toString(),
                    label = row[ConferenceStreamDestinationTable.label],
                    platform = row[ConferenceStreamDestinationTable.platform],
                    status = row[ConferenceStreamTargetTable.status],
                    retries = row[ConferenceStreamTargetTable.retries],
                    failureReason = row[ConferenceStreamTargetTable.failureReason],
                )
            }

    private fun streamRowToDto(
        row: ResultRow,
        roomTitle: String,
    ): ConferenceStreamDto {
        val streamId = row[ConferenceStreamTable.id]
        val startedBy = row[ConferenceStreamTable.startedByMemberId]
        return ConferenceStreamDto(
            id = streamId.toString(),
            roomId = row[ConferenceStreamTable.roomId].toString(),
            roomTitle = roomTitle,
            status = row[ConferenceStreamTable.status],
            layout = row[ConferenceStreamTable.layout],
            latencyMode = row[ConferenceStreamTable.latencyMode],
            startedByMemberId = startedBy.toString(),
            startedByDisplayName = memberDisplayName(startedBy),
            startedAt = row[ConferenceStreamTable.startedAt],
            pausedAt = row[ConferenceStreamTable.pausedAt],
            endedAt = row[ConferenceStreamTable.endedAt],
            restartCount = row[ConferenceStreamTable.restartCount],
            targets = targetDtos(streamId),
            failureReason = row[ConferenceStreamTable.failureReason],
        )
    }

    private fun memberDisplayName(memberId: Uuid): String =
        MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.displayName]

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime()

    private fun String.toStreamUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
}

private const val DEFAULT_MUTATE_RATE_MAX = 30
private const val DEFAULT_READ_RATE_MAX = 60
private val DEFAULT_ACTION_RATE_WINDOW = 1.minutes

private data class PreparedTarget(
    val destinationId: Uuid,
    val plaintextUrl: String,
    val fingerprint: String,
)

private data class StartPreparation(
    val streamId: Uuid,
    val roomName: String,
    val roomTitle: String,
    val targets: List<PreparedTarget>,
)

private data class ResumePreparation(
    val roomName: String,
    val roomTitle: String,
    val layout: ConferenceStreamLayout,
    val latencyMode: ConferenceStreamLatencyMode,
    val participantIdentity: String?,
    val previousRestartCount: Int,
    val urls: List<String>,
)

private data class PausePrep(
    val roomTitle: String,
    val roomName: String?,
    val egressId: String?,
    val alreadyHandled: Boolean,
)

private data class StopPrep(
    val roomTitle: String,
    val roomName: String?,
    val egressId: String?,
    val alreadyTerminal: Boolean,
)
