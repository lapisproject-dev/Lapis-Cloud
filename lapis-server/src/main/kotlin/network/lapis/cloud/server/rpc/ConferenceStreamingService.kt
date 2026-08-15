package network.lapis.cloud.server.rpc

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
import network.lapis.cloud.shared.domain.ConferenceStreamPauseReason
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
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
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
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Non-terminal [ConferenceStreamStatus] values -- "already active" for the one-active-stream-per-room invariant, the cross-room one-active-stream-per-DESTINATION invariant (see [ConferenceStreamingService.startStream]'s `destinationsAlreadyStreaming` check), AND the set [network.lapis.cloud.server.conference.StreamPoller] (and [ConferenceStreamingService.deleteDestination]'s reference guard) treat as "still using this destination". */
private val ACTIVE_STREAM_STATUSES =
    listOf(
        ConferenceStreamStatus.STARTING,
        ConferenceStreamStatus.LIVE,
        // V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- a PAUSING stream
        // is emphatically still "in use" for every invariant this set backs (one-active-stream-per-
        // room, cross-room destination exclusivity, deleteDestination's reference guard,
        // getActiveStream's transparency read): it has not yet been confirmed stopped (D3), so
        // treating it as inactive would let a second stream start against the same room/destination
        // while the first might still be publishing.
        ConferenceStreamStatus.PAUSING,
        ConferenceStreamStatus.PAUSED,
        ConferenceStreamStatus.STOPPING,
    )

/** DoS guard for [ConferenceStreamingService.listStreams] -- same class of cap [ConferenceRecordingService.listRecordings]'s own limit enforces. */
private const val MAX_LIST_RESULTS = 200

/** [ConferenceStreamDestinationDto.streamKeyMask] -- ALWAYS this literal, see that field's own KDoc. */
private const val STREAM_KEY_MASK = "********"

/**
 * Security-audit MAJOR-1/MINOR-6 fix -- mirrors
 * [network.lapis.cloud.server.conference.DefaultSecretBallotStreamGuard]'s own (file-private)
 * `TERMINAL_EGRESS_STATUSES` set (`livekit.EgressStatus`'s four terminal values), duplicated rather
 * than shared for the same "that constant is private to its own file" reason that class's own KDoc
 * gives for duplicating it again from [network.lapis.cloud.server.conference.StreamPoller]. Used by
 * [ConferenceStreamingService.awaitEgressStopConfirmation].
 */
private val TERMINAL_EGRESS_STATUSES = setOf("EGRESS_COMPLETE", "EGRESS_FAILED", "EGRESS_ABORTED", "EGRESS_LIMIT_REACHED")

/** Polling interval for [ConferenceStreamingService.awaitEgressStopConfirmation] -- same value as [network.lapis.cloud.server.conference.DefaultSecretBallotStreamGuard]'s own `QUIESCE_VERIFY_POLL_MS`. */
private const val STOP_VERIFY_POLL_MS = 500L

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
 * ## `pauseStream`/`resumeStream`/`stopStream` -- best-effort `StopEgress` REQUEST, but a CONFIRMED
 * finalizing write
 *
 * `StopEgress` is called OUTSIDE any transaction and wrapped in its own `try`/`catch` that only
 * WARN-logs on [LiveKitAdminException] -- a failed REQUEST never blocks the call outright. This
 * mirrors [network.lapis.cloud.server.conference.RecordingPoller.handleStopping]'s own "StopEgress
 * failed -- log and continue" discipline: a transient LiveKit outage must never strand a moderator's
 * pause/stop action, and `StopEgress` is itself idempotent to call again (LiveKit simply reports
 * current status) so nothing is lost by not retrying the request itself here. Security-audit
 * MAJOR-1/MINOR-6 (`stopStream`) / round-4 R4-3 (`pauseStream`) fix -- the FINALIZING write
 * (`PAUSED`/`ENDED`) is a separate matter from the best-effort request above: both methods poll
 * `ListEgress` afterwards until the egress is confirmed gone/terminal (capped at
 * `pauseVerifyTimeoutSeconds`) before writing the terminal-for-polling-purposes status, and route
 * through an intermediate `PAUSING`/`STOPPING` row in the meantime -- a genuinely still-running egress
 * (StopEgress failed, or simply has not caught up yet) leaves the row `PAUSING`/`STOPPING` rather than
 * lying that it is quiesced; [network.lapis.cloud.server.conference.StreamPoller]'s own
 * `handlePausing`/`handleStopping` retry the same confirmation on a later tick regardless of this
 * call's own outcome.
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
        requireWithinRate(limiter = readRateLimiter, memberId = current.memberId)
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
        requireWithinLoginRate(limiter = destinationRateLimiter, memberId = current.memberId)

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
            val ciphertext = secretBox!!.seal(plaintext = streamKey, aad = newId.toString())
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
        requireWithinLoginRate(limiter = destinationRateLimiter, memberId = current.memberId)

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
                    it[streamKeyCiphertext] = secretBox!!.seal(plaintext = newStreamKey, aad = destUuid.toString())
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
        requireWithinLoginRate(limiter = destinationRateLimiter, memberId = current.memberId)
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
        requireWithinLoginRate(limiter = destinationRateLimiter, memberId = current.memberId)
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
        requireWithinRate(limiter = readRateLimiter, memberId = current.memberId)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
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
                requireModeratorOrPrivileged(room = room, current = current)

                // V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- hard-wired,
                // never disableable via the UI (Konzeptnotiz): a room bound to a Sitzung with a
                // currently-open secret ballot must never be allowed to START publishing in the first
                // place. roomUuid is already forUpdate()-locked above -- see SecretBallotStreamLock
                // KDoc "Locking order" for why this ordering is what serializes this check against a
                // concurrent ElectionService.openVoting/SystemicConsensusService.freezeOptions.
                if (SecretBallotStreamLock.hasOpenSecretBallot(roomUuid)) {
                    throw ConflictException(
                        "Für diese Sitzung läuft eine geheime Abstimmung -- ein Live-Stream kann erst danach gestartet werden.",
                    )
                }

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
                        PreparedTarget(
                            destinationId = destId,
                            plaintextUrl = plaintextUrl,
                            fingerprint = StreamUrlFingerprint.of(plaintextUrl),
                        )
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
                StartPreparation(
                    streamId = newStreamId,
                    roomName = room[ConferenceRoomTable.livekitRoomName],
                    roomTitle = room[ConferenceRoomTable.title],
                    targets = preparedTargets,
                )
            }

        // OUTSIDE the transaction -- never a network call inside an open one. See
        // IConferenceStreamingService KDoc "startStream DOES call LiveKit synchronously".
        val egressResult =
            runCatching {
                val urls = prep.targets.map { it.plaintextUrl }
                if (layout == ConferenceStreamLayout.SINGLE_PARTICIPANT) {
                    liveKitEgressClient.startParticipantEgress(
                        roomName = prep.roomName,
                        identity = participantIdentity!!,
                        latencyMode = latencyMode,
                        rtmpUrls = urls,
                    )
                } else {
                    liveKitEgressClient.startRoomCompositeEgress(
                        roomName = prep.roomName,
                        layout = layout,
                        latencyMode = latencyMode,
                        rtmpUrls = urls,
                    )
                }
            }

        return transaction {
            val now = nowLocalDateTime()
            // V1.0 Videokonferenzen, Wave 9, R1 race-fix -- forUpdate() re-read: the hard-wired
            // pre-check in Tx1 ran BEFORE the (unguarded, asynchronous) LiveKit call above; a
            // concurrent ElectionService.openVoting/SystemicConsensusService.freezeOptions may, in that
            // window, have already flipped this STARTING row to PAUSING via
            // ConferenceStreamPauseCoordinator.markPausingForSecretBallot. Without this re-check, the
            // unconditional update below could stomp that PAUSING write straight back to LIVE if this
            // Tx2's OWN hasOpenSecretBallot snapshot happens to be read before that concurrent
            // transaction's commit -- exactly the failure the wave's own R1 race test (startStream vs
            // openVoting) is designed to catch. Mirrors restartEgressForStream's own analogous re-check
            // (its R3 fix) one transaction earlier in this same file.
            val currentRow =
                ConferenceStreamTable
                    .selectAll()
                    .where { ConferenceStreamTable.id eq prep.streamId }
                    .forUpdate()
                    .single()
            if (currentRow[ConferenceStreamTable.status] != ConferenceStreamStatus.STARTING) {
                logger.warn {
                    "startStream: stream ${prep.streamId} left STARTING (now ${currentRow[ConferenceStreamTable.status]}) before its " +
                        "LiveKit call returned -- not overwriting its status"
                }
                // Still record the egress id on success so nothing already running is silently leaked --
                // never touches `status`/`pauseReason`, whatever a concurrent pause/stop already wrote there
                // stands. EXCEPT security-audit MINOR-7: if the row is now PAUSED, that PAUSED is a LIE --
                // StreamPoller.handlePausing's own orphan-reconciliation branch can conclude "nothing is
                // publishing" and write PAUSED while THIS LiveKit call is still in flight (see that
                // method's KDoc "orphan" and ConferenceStreamingService's own class KDoc security-audit
                // note), because at that moment egress_id was still null in the DB -- the egress this call
                // just created was never actually asked to stop. Re-flip to PAUSING (pauseReason is
                // already SECRET_BALLOT, written by ConferenceStreamPauseCoordinator and left untouched by
                // markPaused) so StreamPoller's normal PAUSING loop picks the freshly-recorded egress id up
                // again and runs its StopEgress+ListEgress confirmation before this stream is EVER trusted
                // as quiesced again -- never silently leave a real, live egress behind a PAUSED status.
                //
                // Security-audit-round-2 F1 fix -- the SAME lie can happen with STOPPING/ENDED: a
                // concurrent stopStream can commit STOPPING (egress id not yet known, see that method's
                // own KDoc) and, if it had nothing to wait on, finalize all the way to ENDED, WHILE this
                // call's own LiveKit request was still in flight. ENDED is terminal to StreamPoller (never
                // revisited, see NON_TERMINAL_STREAM_STATUSES) and to SecretBallotStreamLock
                // .requireStreamQuiescedForBallot's fail-closed blocklist (STARTING/LIVE/PAUSING/STOPPING
                // only) -- an egress that starts publishing AFTER the row already says ENDED would
                // otherwise never be stopped and would never block a secret ballot either. Resurrect the
                // row back to STOPPING (clearing `endedAt` if it was already set) and record the fresh
                // egress id so StreamPoller.handleStopping's own NON_TERMINAL_STREAM_STATUSES sweep picks
                // it up again on its very next tick and runs the normal StopEgress+ListEgress-confirm
                // dance -- same "resurrect, don't silently leak" posture `wasFailOpenPaused` above already
                // establishes for the PAUSED case.
                val statusAtAbandon = currentRow[ConferenceStreamTable.status]
                val wasFailOpenPaused = statusAtAbandon == ConferenceStreamStatus.PAUSED
                val wasStoppedOrEnded =
                    statusAtAbandon == ConferenceStreamStatus.STOPPING || statusAtAbandon == ConferenceStreamStatus.ENDED
                egressResult.onSuccess { info ->
                    ConferenceStreamTable.update({ ConferenceStreamTable.id eq prep.streamId }) {
                        it[livekitEgressId] = info.egressId
                        if (wasFailOpenPaused) {
                            it[status] = ConferenceStreamStatus.PAUSING
                        } else if (wasStoppedOrEnded) {
                            it[status] = ConferenceStreamStatus.STOPPING
                            it[endedAt] = null
                        }
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
                val abandonedRow = ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq prep.streamId }.single()
                return@transaction streamRowToDto(row = abandonedRow, roomTitle = prep.roomTitle)
            }
            egressResult
                .onSuccess { info ->
                    // V1.0 Videokonferenzen, Wave 9, D3/§6.3 race-fix -- the hard-wired pre-check
                    // above ran BEFORE the (unguarded, asynchronous) LiveKit call; a secret ballot may
                    // have opened WHILE that call was in flight. Re-check here, in this fresh
                    // transaction, before deciding the final status -- livekit_egress_id is ALWAYS
                    // written on success either way (never leak a real, running egress by failing to
                    // record its id). Identical to restartEgressForStream's own onSuccess branch
                    // below -- see that function's KDoc "The D3/§6.3 race-fix".
                    val roomStillClean = !SecretBallotStreamLock.hasOpenSecretBallot(roomUuid)
                    ConferenceStreamTable.update({ ConferenceStreamTable.id eq prep.streamId }) {
                        it[livekitEgressId] = info.egressId
                        if (roomStillClean) {
                            it[status] = ConferenceStreamStatus.LIVE
                        } else {
                            // A secret ballot opened while this stream's LiveKit call was in flight --
                            // StreamPoller's own PAUSING handling stops this freshly-started egress on
                            // its next tick (see handlePausing KDoc).
                            it[status] = ConferenceStreamStatus.PAUSING
                            it[pauseReason] = ConferenceStreamPauseReason.SECRET_BALLOT
                        }
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
            streamRowToDto(row = row, roomTitle = prep.roomTitle)
        }
    }

    override suspend fun pauseStream(streamId: String): ConferenceStreamDto {
        val current = resolveCurrentMember(call)
        requireStreamingEnabled()
        requireWithinRate(limiter = mutateRateLimiter, memberId = current.memberId)
        val streamUuid = streamId.toStreamUuid()

        val prep =
            transaction {
                val row =
                    ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.singleOrNull()
                        ?: throw NotFoundException("Conference stream $streamUuid not found")
                val room =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq row[ConferenceStreamTable.roomId] }.singleOrNull()
                        ?: throw NotFoundException("Conference room for stream $streamUuid not found")
                requireModeratorOrPrivileged(room = room, current = current)

                // Idempotent -- see IConferenceStreamingService.pauseStream KDoc. Only a LIVE
                // stream has an active egress worth stopping.
                if (row[ConferenceStreamTable.status] != ConferenceStreamStatus.LIVE) {
                    // V1.0 Videokonferenzen, Wave 9, D4 -- one-way escalation: a moderator manually
                    // pausing WHILE the row is already PAUSING or PAUSED+SECRET_BALLOT takes control
                    // away from the automatic secret-ballot pause -- pause_reason flips to MANUAL so
                    // SecretBallotStreamGuard.resumeStreamsForMeeting never auto-resumes it again (see
                    // ConferenceStreamPauseReason KDoc). Never runs the other way (MANUAL never
                    // reverts to SECRET_BALLOT here). No StopEgress needed in this branch -- either
                    // nothing is running yet (PAUSING) or nothing is running anymore (PAUSED), just
                    // the DB write.
                    val status = row[ConferenceStreamTable.status]
                    val pauseReasonNow = row[ConferenceStreamTable.pauseReason]
                    val needsEscalation =
                        status == ConferenceStreamStatus.PAUSING ||
                            (status == ConferenceStreamStatus.PAUSED && pauseReasonNow == ConferenceStreamPauseReason.SECRET_BALLOT)
                    if (needsEscalation) {
                        ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamUuid }) {
                            it[pauseReason] = ConferenceStreamPauseReason.MANUAL
                        }
                    }
                    return@transaction PausePrep(
                        roomTitle = room[ConferenceRoomTable.title],
                        roomName = null,
                        egressId = null,
                        alreadyHandled = true,
                    )
                }

                // Security-audit round-4 R4-3 fix -- route through PAUSING with a REAL StopEgress
                // confirmation before ever writing PAUSED, mirroring stopStream's own two-transaction
                // shape byte-for-byte (see that method's own "Security-audit MAJOR-1/MINOR-6 fix"
                // comment for the full reasoning): PAUSED became security-load-bearing this wave --
                // SecretBallotStreamLock.requireStreamQuiescedForBallot trusts it BLINDLY to mean
                // "nothing is publishing" -- so a best-effort, unconfirmed StopEgress (the ORIGINAL
                // shape of this method, unconditionally writing PAUSED right after requesting the
                // stop) is no longer enough for a MANUAL pause either. `pauseReason = MANUAL` is
                // written HERE, not after confirmation, for the same reason
                // DefaultSecretBallotStreamGuard never writes SECRET_BALLOT after the fact either --
                // this IS a manual pause from the moment it is requested, regardless of how long
                // confirmation takes.
                ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamUuid }) {
                    it[ConferenceStreamTable.status] = ConferenceStreamStatus.PAUSING
                    it[pauseReason] = ConferenceStreamPauseReason.MANUAL
                }
                PausePrep(
                    roomTitle = room[ConferenceRoomTable.title],
                    roomName = room[ConferenceRoomTable.livekitRoomName],
                    egressId = row[ConferenceStreamTable.livekitEgressId],
                    alreadyHandled = false,
                )
            }
        if (prep.alreadyHandled) {
            return transaction {
                val row = ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.single()
                streamRowToDto(row = row, roomTitle = prep.roomTitle)
            }
        }

        // Best-effort REQUEST, OUTSIDE any transaction -- see class KDoc "pauseStream/resumeStream/
        // stopStream -- best-effort StopEgress". Security-audit round-4 R4-3 fix -- StopEgress alone
        // is a REQUEST, not a confirmation (same discipline stopStream's own MAJOR-1/MINOR-6 fix and
        // DefaultSecretBallotStreamGuard.quiesceStreamsForMeeting already apply): confirmedStopped
        // below, via the SAME awaitEgressStopConfirmation loop stopStream uses (same
        // pauseVerifyTimeoutSeconds budget, no new env var), is what actually decides whether this
        // call may finalize PAUSED.
        var confirmedStopped = true
        if (prep.roomName != null && prep.egressId != null) {
            try {
                liveKitEgressClient.stopEgress(roomName = prep.roomName, egressId = prep.egressId)
            } catch (e: LiveKitAdminException) {
                logger.warn { "pauseStream: StopEgress failed for stream $streamUuid: ${e.message}" }
            }
            confirmedStopped = awaitEgressStopConfirmation(roomName = prep.roomName, egressId = prep.egressId)
        }

        if (!confirmedStopped) {
            // The row is already PAUSING (written above) -- left untouched here.
            // StreamPoller.handlePausing retries the same confirmation on its next tick, same
            // fail-closed "leave it PAUSING, retry later" posture stopStream's own timeout branch and
            // DefaultSecretBallotStreamGuard.quiesceStreamsForMeeting's own timeout branch document.
            logger.warn {
                "pauseStream: timed out confirming egress ${prep.egressId} stopped for stream $streamUuid -- left " +
                    "PAUSING, StreamPoller retries on its next tick"
            }
            return transaction {
                val row = ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.single()
                streamRowToDto(row = row, roomTitle = prep.roomTitle)
            }
        }

        return transaction {
            val now = nowLocalDateTime()
            // Security-audit round-4 R4-3 fix -- same egress-id-guarded predicate as stopStream's own
            // NEU-1 fix / StreamPoller.markPaused's own NEU-2 fix / DefaultSecretBallotStreamGuard
            // .markPaused's own R4-1 fix: only finalize PAUSED if the row's CURRENT
            // `livekit_egress_id` still matches (or remains unset, i.e. never set) the id whose stop
            // THIS call just confirmed above -- otherwise a concurrent
            // startStream/restartEgressForStream "abandoned" branch already resurrected this row onto
            // a FRESH, actually-publishing egress in the window between that confirmation and this
            // write, and finalizing to PAUSED here would strand the fresh egress forever (PAUSED is
            // never revisited by anything). If the predicate does not match, the write is skipped
            // entirely -- the row is left exactly as the resurrection wrote it, still PAUSING under
            // StreamPoller's own handlePausing sweep on the very next tick.
            val confirmedEgressId = prep.egressId
            val updated =
                ConferenceStreamTable.update({
                    (ConferenceStreamTable.id eq streamUuid) and
                        (ConferenceStreamTable.status eq ConferenceStreamStatus.PAUSING) and
                        (
                            ConferenceStreamTable.livekitEgressId.isNull() or
                                (ConferenceStreamTable.livekitEgressId eq confirmedEgressId)
                        )
                }) {
                    it[status] = ConferenceStreamStatus.PAUSED
                    it[pausedAt] = now
                    // pauseReason left untouched -- already MANUAL, written above before the
                    // confirmation loop started.
                }
            if (updated == 0) {
                logger.warn {
                    "pauseStream: stream $streamUuid was resurrected with a new egress id while this call's own " +
                        "stop confirmation was pending -- NOT finalizing to PAUSED, leaving it for StreamPoller's " +
                        "own next tick"
                }
                val row = ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.single()
                return@transaction streamRowToDto(row = row, roomTitle = prep.roomTitle)
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
            streamRowToDto(row = row, roomTitle = prep.roomTitle)
        }
    }

    override suspend fun resumeStream(streamId: String): ConferenceStreamDto {
        val current = resolveCurrentMember(call)
        requireStreamingEnabled()
        requireWithinRate(limiter = mutateRateLimiter, memberId = current.memberId)
        val streamUuid = streamId.toStreamUuid()

        // Authorization + the moderator-facing "not paused" rejection -- kept as an explicit,
        // immediate ConflictException here (rather than folded into restartEgressForStream's own
        // defensive re-check below) so a normal, non-racing manual resumeStream call still gets a
        // clear error instead of silently no-op'ing. restartEgressForStream's OWN forUpdate()
        // re-read of the row is what actually closes the TOCTOU window between this check and the
        // shared function's own transaction -- see that function's KDoc "respects an already-
        // terminal row" (R3 in the wave's race test matrix).
        val roomTitle =
            transaction {
                val row =
                    ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.singleOrNull()
                        ?: throw NotFoundException("Conference stream $streamUuid not found")
                val room =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq row[ConferenceStreamTable.roomId] }.singleOrNull()
                        ?: throw NotFoundException("Conference room for stream $streamUuid not found")
                requireModeratorOrPrivileged(room = room, current = current)
                // V1.0 Videokonferenzen, Wave 9 -- moderator-facing rejection for the common,
                // non-racing case (restartEgressForStream's own transaction independently re-checks
                // this again for the auto-resume caller's sake, see that function's KDoc "The D3/§6.3
                // race-fix" -- this earlier check exists purely so a normal manual resumeStream call
                // gets a clear, specific error instead of the generic "not paused" one below, which
                // would otherwise fire here too since a PAUSING stream is not PAUSED either).
                if (SecretBallotStreamLock.hasOpenSecretBallot(row[ConferenceStreamTable.roomId])) {
                    throw ConflictException(
                        "Der Stream ist wegen einer laufenden geheimen Abstimmung unterbrochen und kann nicht manuell fortgesetzt werden.",
                    )
                }
                if (row[ConferenceStreamTable.status] != ConferenceStreamStatus.PAUSED) {
                    throw ConflictException("Conference stream $streamUuid is not paused")
                }
                room[ConferenceRoomTable.title]
            }

        // See restartEgressForStream KDoc -- this is the SAME two-transaction, synchronous-LiveKit-
        // call kernel startStream's own two transactions use, extracted so
        // network.lapis.cloud.server.conference.SecretBallotStreamGuard's auto-resume path (Wave 9
        // "Stream-Pause bei geheimen Abstimmungen") does not have to duplicate it.
        restartEgressForStream(
            streamId = streamUuid,
            liveKitEgressClient = liveKitEgressClient,
            streamingConfig = streamingConfig,
            actorMemberId = current.memberId,
            actorRole = current.role,
        )

        return transaction {
            val row = ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.single()
            streamRowToDto(row = row, roomTitle = roomTitle)
        }
    }

    override suspend fun stopStream(streamId: String): ConferenceStreamDto {
        val current = resolveCurrentMember(call)
        requireStreamingEnabled()
        requireWithinRate(limiter = mutateRateLimiter, memberId = current.memberId)
        val streamUuid = streamId.toStreamUuid()

        val prep =
            transaction {
                val row =
                    ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.singleOrNull()
                        ?: throw NotFoundException("Conference stream $streamUuid not found")
                val room =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq row[ConferenceStreamTable.roomId] }.singleOrNull()
                        ?: throw NotFoundException("Conference room for stream $streamUuid not found")
                requireModeratorOrPrivileged(room = room, current = current)

                val status = row[ConferenceStreamTable.status]
                // Idempotent once already ENDED/FAILED -- see IConferenceStreamingService
                // .stopStream KDoc.
                if (status == ConferenceStreamStatus.ENDED || status == ConferenceStreamStatus.FAILED) {
                    return@transaction StopPrep(
                        roomTitle = room[ConferenceRoomTable.title],
                        roomName = null,
                        egressId = null,
                        alreadyTerminal = true,
                    )
                }
                ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamUuid }) {
                    it[ConferenceStreamTable.status] =
                        ConferenceStreamStatus.STOPPING
                }
                StopPrep(
                    roomTitle = room[ConferenceRoomTable.title],
                    roomName = room[ConferenceRoomTable.livekitRoomName],
                    // V1.0 Videokonferenzen, Wave 9, Stolperfalle §9.5 -- widened from LIVE-only:
                    // a PAUSING stream's egress may still be running (StopEgress requested but not yet
                    // confirmed, see D3) and would otherwise be orphaned at LiveKit if stopStream is
                    // called while it is still PAUSING.
                    //
                    // Security-audit-round-2 F1 fix -- further widened to STARTING and PAUSED. STARTING:
                    // this row's own egress id is normally still null at this exact read (startStream's
                    // own Tx2 has not landed yet) -- widening the condition alone does not close that race,
                    // the actual fix is the "abandoned" branches in startStream/restartEgressForStream
                    // below, which record the freshly-started egress id and resurrect an already-ENDED row
                    // back to STOPPING once they discover it (see those call sites' own comments). PAUSED:
                    // a PAUSED row can legitimately carry a STALE id (StreamPoller.markPaused/pauseStream
                    // never clear `livekitEgressId` when moving into PAUSED, and restartEgressForStream's
                    // own atomic PAUSED->STARTING claim does not clear it either) -- worth a defensive
                    // re-confirm here regardless of whether it turns out to already be gone.
                    egressId =
                        row[ConferenceStreamTable.livekitEgressId].takeIf {
                            status == ConferenceStreamStatus.LIVE ||
                                status == ConferenceStreamStatus.PAUSING ||
                                status == ConferenceStreamStatus.STARTING ||
                                status == ConferenceStreamStatus.PAUSED
                        },
                    alreadyTerminal = false,
                )
            }
        if (prep.alreadyTerminal) {
            return transaction {
                val row = ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.single()
                streamRowToDto(row = row, roomTitle = prep.roomTitle)
            }
        }

        // Best-effort, OUTSIDE any transaction -- see class KDoc. Security-audit MAJOR-1/MINOR-6 fix
        // -- StopEgress alone is a REQUEST, not a confirmation (LiveKit's own semantics, see
        // DefaultSecretBallotStreamGuard KDoc "Quiescing algorithm"): this method used to write ENDED
        // immediately afterwards regardless, so requireStreamQuiescedForBallot's fail-closed gate had
        // nothing left to block a secret ballot on the instant this stream's row said ENDED, even
        // though the egress it just asked to stop might still be publishing. Mirrors
        // DefaultSecretBallotStreamGuard.quiesceStreamsForMeeting's own ListEgress-until-terminal-or-
        // gone confirmation loop byte-for-byte (same pauseVerifyTimeoutSeconds budget).
        var confirmedStopped = true
        if (prep.roomName != null && prep.egressId != null) {
            try {
                liveKitEgressClient.stopEgress(roomName = prep.roomName, egressId = prep.egressId)
            } catch (e: LiveKitAdminException) {
                logger.warn { "stopStream: StopEgress failed for stream $streamUuid: ${e.message}" }
            }
            confirmedStopped = awaitEgressStopConfirmation(roomName = prep.roomName, egressId = prep.egressId)
        }

        if (!confirmedStopped) {
            // The row is already STOPPING (written by Tx1 above) -- left untouched here.
            // StreamPoller.handleStopping retries the same confirmation on its next tick (see that
            // method's own KDoc), same "leave it PAUSING/STOPPING, retry later" fail-closed posture
            // DefaultSecretBallotStreamGuard.quiesceStreamsForMeeting's own timeout branch documents.
            logger.warn {
                "stopStream: timed out confirming egress ${prep.egressId} stopped for stream $streamUuid -- left " +
                    "STOPPING, StreamPoller retries on its next tick"
            }
            return transaction {
                val row = ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.single()
                streamRowToDto(row = row, roomTitle = prep.roomTitle)
            }
        }

        return transaction {
            val now = nowLocalDateTime()
            // Security-audit round-3 NEU-1 fix -- the row's CURRENT livekit_egress_id must still
            // match (or remain null, i.e. never set) the id whose stop THIS call just confirmed above
            // via awaitEgressStopConfirmation (prep.egressId, possibly null if none was ever known) --
            // otherwise a concurrent startStream/restartEgressForStream "abandoned" branch already
            // resurrected this row onto a FRESH, actually-publishing egress in the window between
            // that confirmation and this write (see those call sites' own KDoc "abandoned"), and
            // finalizing to ENDED here would strand the fresh egress forever: ENDED is terminal to
            // StreamPoller's own NON_TERMINAL_STREAM_STATUSES sweep, so nothing would ever ask it to
            // stop again. Verified live-reproducible (security-audit round 3): the OLD unconditional
            // write here is what let a `stopStream` confirming an OLD egress silently overwrite a
            // resurrection that had already attached a NEW, still-publishing egress to the same row.
            // If the predicate below does not match, the write is skipped entirely and the row is left
            // exactly as the resurrection wrote it (STOPPING/PAUSING with the fresh id) --
            // StreamPoller picks it up on its very next tick like any other non-terminal row.
            val confirmedEgressId = prep.egressId
            val updated =
                ConferenceStreamTable.update({
                    (ConferenceStreamTable.id eq streamUuid) and
                        (
                            ConferenceStreamTable.livekitEgressId.isNull() or
                                (ConferenceStreamTable.livekitEgressId eq confirmedEgressId)
                        )
                }) {
                    it[status] = ConferenceStreamStatus.ENDED
                    it[endedAt] = now
                }
            if (updated == 0) {
                logger.warn {
                    "stopStream: stream $streamUuid was resurrected with a new egress id while this call's own " +
                        "stop confirmation was pending -- NOT finalizing to ENDED, leaving it for StreamPoller's " +
                        "own next tick"
                }
                val row = ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.single()
                return@transaction streamRowToDto(row = row, roomTitle = prep.roomTitle)
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
            streamRowToDto(row = row, roomTitle = prep.roomTitle)
        }
    }

    /**
     * Security-audit MAJOR-1/MINOR-6 fix -- [stopStream]'s own confirmation loop, functionally
     * identical to [network.lapis.cloud.server.conference.DefaultSecretBallotStreamGuard]'s private
     * `awaitEgressStopped`, duplicated (not shared) for the same "no instance to call it on, and that
     * one is private to its own file" reason [restartRtmpUrl] gives for duplicating [buildRtmpUrl].
     * Polls `ListEgress` every [STOP_VERIFY_POLL_MS] until [egressId] is either gone from the list or
     * reports a [TERMINAL_EGRESS_STATUSES] status, capped at [streamingConfig]'s own
     * `pauseVerifyTimeoutSeconds` (reused for stop-confirmation too -- same budget, same knob, no new
     * env var). `false` on timeout -- the caller leaves the row `STOPPING` rather than writing `ENDED`.
     */
    private suspend fun awaitEgressStopConfirmation(
        roomName: String,
        egressId: String,
    ): Boolean =
        withTimeoutOrNull(streamingConfig.pauseVerifyTimeoutSeconds.seconds) {
            while (true) {
                val egresses =
                    try {
                        liveKitEgressClient.listEgress(roomName)
                    } catch (e: LiveKitAdminException) {
                        logger.warn { "stopStream: ListEgress failed while confirming egress $egressId stopped: ${e.message}" }
                        delay(STOP_VERIFY_POLL_MS)
                        continue
                    }
                val info = egresses.firstOrNull { it.egressId == egressId }
                if (info == null || info.status in TERMINAL_EGRESS_STATUSES) return@withTimeoutOrNull true
                delay(STOP_VERIFY_POLL_MS)
            }
        } == true

    // ── Transparency read ────────────────────────────────────────────────

    override suspend fun getActiveStream(roomId: String): List<ConferenceStreamDto> {
        val current = resolveCurrentMember(call)
        requireStreamingEnabled()
        requireWithinRate(limiter = readRateLimiter, memberId = current.memberId)
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
            val status = requireRoomEntryAuthorization(roomRow = room, current = current)
            requireGuestHasJoinedRoom(roomId = roomUuid, current = current, status = status)
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
            listOf(streamRowToDto(row = row, roomTitle = room[ConferenceRoomTable.title]))
        }
    }

    override suspend fun listStreams(roomId: String?): List<ConferenceStreamDto> {
        val current = resolveCurrentMember(call)
        requireStreamingEnabled()
        requireWithinRate(limiter = readRateLimiter, memberId = current.memberId)
        val parsedRoomId = roomId?.toStreamUuid()
        return transaction {
            if (parsedRoomId != null) {
                val room =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq parsedRoomId }.singleOrNull()
                        ?: throw NotFoundException("Conference room $parsedRoomId not found")
                requireModeratorOrPrivileged(room = room, current = current)
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
                    streamRowToDto(row = row, roomTitle = title)
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
        val key = secretBox!!.open(sealed = row[ConferenceStreamDestinationTable.streamKeyCiphertext], aad = destinationId.toString())
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
            pauseReason = row[ConferenceStreamTable.pauseReason],
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

/**
 * V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- the egress-(re)start
 * kernel [ConferenceStreamingService.resumeStream] and
 * [network.lapis.cloud.server.conference.SecretBallotStreamGuard.resumeStreamsForMeeting]'s
 * auto-resume path BOTH need, extracted into one top-level `internal` function so neither has to
 * duplicate it. [actorMemberId]/[actorRole] are `null` for a system-initiated auto-resume (the
 * caller is `closeVoting`/`closeRating`/`abortElection`/`abortSystemicConsensus`, not a member
 * click) -- [network.lapis.cloud.server.audit.AuditLogRecorder.record] accepts a `null` actor for
 * exactly this reason.
 *
 * ## Two-transaction, synchronous-LiveKit-call pattern -- identical to [ConferenceStreamingService
 * .startStream]/the ORIGINAL (pre-extraction) `resumeStream`
 *
 * Transaction 1: `forUpdate()`-locks the row, re-derives the plaintext target URLs/fingerprints
 * fresh, resets every target row to [ConferenceStreamTargetStatus.PENDING], and flips the stream to
 * [ConferenceStreamStatus.STARTING]. Then, OUTSIDE any transaction, the actual LiveKit
 * `Start...Egress` call. Then transaction 2 stores the result.
 *
 * ## Respects an already-terminal row (R3 in the wave's race test matrix)
 *
 * Transaction 1 re-checks [ConferenceStreamStatus.PAUSED] itself, under its OWN `forUpdate()` lock
 * -- if a concurrent [ConferenceStreamingService.stopStream] (or a previous, still-in-flight call to
 * THIS function) already moved the row past [ConferenceStreamStatus.PAUSED] by the time this
 * transaction acquires the lock, this function does NOT resurrect it: it returns the row's CURRENT
 * status untouched, no LiveKit call is ever made. [ConferenceStreamingService.resumeStream] itself
 * still throws a moderator-facing [ConflictException] for the common, non-racing "already resumed/
 * not paused" case (see that method's own comment) -- this function's own re-check exists purely to
 * close the TOCTOU window between that earlier check and this function's own transaction, which
 * matters for the auto-resume caller (no earlier check happens there at all).
 *
 * ## The D3/§6.3 race-fix, applied here identically to [ConferenceStreamingService.startStream]'s
 * own transaction 2 (a later wave step)
 *
 * On a successful LiveKit call, transaction 2 re-checks
 * [network.lapis.cloud.server.rpc.SecretBallotStreamLock.hasOpenSecretBallot] for the stream's room
 * BEFORE deciding the final status: if still clean, [ConferenceStreamStatus.LIVE]; if a secret
 * ballot opened WHILE the (asynchronous, unguarded) LiveKit call was in flight, the row is written
 * [ConferenceStreamStatus.PAUSING] + `pauseReason = `[ConferenceStreamPauseReason.SECRET_BALLOT]
 * instead -- `livekit_egress_id` is ALWAYS written on success either way (never leak a real, running
 * egress by failing to record its id), and
 * [network.lapis.cloud.server.conference.StreamPoller] (a later wave step) stops the freshly-started
 * egress on its next tick. This is the fail-closed guarantee: a secret ballot opened during ANY
 * window of this function's execution can never coexist with a genuinely `LIVE` stream.
 *
 * ## `restartCount` is incremented even when the LiveKit call fails
 *
 * Mirrors the ORIGINAL `resumeStream`'s own behaviour byte-for-byte (see 9.3 in the wave's own
 * "Stolperfallen" notes) -- a restart ATTEMPT happened, whether or not it succeeded, and changing
 * this now would silently diverge existing tests that assert on it.
 *
 * ## `secretBox` -- derived from the caller's OWN [streamingConfig], not reloaded from real env
 *
 * This function has no [ConferenceStreamingService] instance to borrow `secretBox` from (it is
 * deliberately a free function, callable from a system-initiated context with no `ApplicationCall`),
 * but [streamingConfig] itself IS threaded through as a parameter by every caller (each already has
 * its own validated instance -- [ConferenceStreamingService.resumeStream] via its constructor field,
 * [network.lapis.cloud.server.conference.DefaultSecretBallotStreamGuard.resumeStreamsForMeeting] via
 * its own constructor field, [network.lapis.cloud.server.conference.StreamPoller] via its own
 * constructor field). Bug found+fixed while implementing the Wave 9 E2E journey test
 * (`SecretBallotStreamPauseJourneyTest`): this function used to call [ConferenceStreamingConfig.load]
 * itself, reading REAL `System.getenv` regardless of which (possibly test-injected, possibly
 * differently-configured) [ConferenceStreamingConfig] the calling [ConferenceStreamingService]/
 * [network.lapis.cloud.server.conference.DefaultSecretBallotStreamGuard]/
 * [network.lapis.cloud.server.conference.StreamPoller] instance was actually built with -- so
 * whenever the REAL environment had no `LAPIS_SECRET_ENCRYPTION_KEY` set (true for every plain
 * `./gradlew test` run in this sandbox, which injects the key via [ConferenceStreamingConfig.load]'s
 * own `env` lambda parameter instead of a real environment variable, see e.g.
 * [network.lapis.cloud.server.rpc.ConferenceStreamingServiceTest]'s own `ENABLED_STREAMING_CONFIG`),
 * `secretBox` silently resolved to `null` here even though the CALLER's own config had a perfectly
 * valid key -- so this function always WARN-logged and left the row `PAUSED`, never actually
 * restarting it. Confirmed as a genuine, pre-existing failure independent of this new test:
 * `ConferenceStreamingServiceTest`'s own "pauseStream then resumeStream" test already failed against
 * this exact bug before this fix. If [streamingConfig] itself is missing an encryption key (streaming
 * was disabled/misconfigured AFTER a stream was already started and paused -- an operator
 * misconfiguration, not a reachable steady state for a caller with a validated config), this function
 * still WARN-logs and leaves the row exactly as it found it (still [ConferenceStreamStatus.PAUSED])
 * rather than crashing -- same fail-closed posture as every other error path here.
 */
internal suspend fun restartEgressForStream(
    streamId: Uuid,
    liveKitEgressClient: LiveKitEgressClient,
    streamingConfig: ConferenceStreamingConfig,
    actorMemberId: Uuid?,
    actorRole: AccountRole?,
): ConferenceStreamStatus {
    val secretBox = streamingConfig.secretEncryptionKey?.let { SecretBox(it) }

    val prep =
        transaction {
            // V1.0 Videokonferenzen, Wave 9, R4 race-fix -- atomic conditional claim, NOT
            // `forUpdate()`-SELECT-then-separate-UPDATE: an `UPDATE ... WHERE status = PAUSED` is a
            // single, indivisible row-level write on every SQL engine (the WHERE re-check and the
            // write happen atomically against whatever the row's CURRENT committed state is) -- a
            // concurrent SECOND such UPDATE for the SAME [streamId] can only ever see `claimedRows == 0`
            // once the first has committed. A `forUpdate()` SELECT immediately followed by a SEPARATE
            // UPDATE statement was empirically found NOT to reliably close this window against two
            // genuinely concurrent calls (see the wave's own R4 race test, `closeVoting` on two
            // different Elections of the same meeting racing to auto-resume the SAME stream) --
            // collapsing "check" and "claim" into ONE statement removes the gap between them entirely.
            val claimedRows =
                ConferenceStreamTable.update({
                    (ConferenceStreamTable.id eq streamId) and (ConferenceStreamTable.status eq ConferenceStreamStatus.PAUSED)
                }) {
                    it[status] = ConferenceStreamStatus.STARTING
                }
            val row =
                ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamId }.singleOrNull()
                    ?: throw NotFoundException("Conference stream $streamId not found")

            // See function KDoc "Respects an already-terminal row". `claimedRows == 0` covers BOTH
            // "was never PAUSED to begin with" and "a concurrent claim already won" identically.
            if (claimedRows == 0) {
                return@transaction RestartEgressPrep(preparation = null, currentStatus = row[ConferenceStreamTable.status])
            }
            val room =
                ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq row[ConferenceStreamTable.roomId] }.singleOrNull()
                    ?: throw NotFoundException("Conference room for stream $streamId not found")
            if (secretBox == null) {
                // The claim already flipped this row to STARTING above -- roll it back to PAUSED so it
                // is not stranded in a state with no LiveKit call ever actually in flight for it.
                //
                // Security-audit round-3 NEU-3 fix -- same one-way escalation to MANUAL the MINOR-9
                // branch below already applies for a disabled destination: without it, pauseReason
                // stays SECRET_BALLOT, so StreamPoller.handlePaused's own SECRET_BALLOT auto-resume
                // branch re-enters this exact function on EVERY tick for as long as
                // LAPIS_SECRET_ENCRYPTION_KEY stays unset/invalid -- the same F3 infinite-retry-loop
                // failure mode as the disabled-destination case, just at a third location, holding the
                // room's one-active-stream-per-room slot open indefinitely instead of ever reaching
                // handlePaused's max-duration escalation.
                ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamId }) {
                    it[status] = ConferenceStreamStatus.PAUSED
                    it[pauseReason] = ConferenceStreamPauseReason.MANUAL
                }
                logger.warn {
                    "restartEgressForStream: LAPIS_SECRET_ENCRYPTION_KEY unset/invalid -- cannot restart stream $streamId, " +
                        "leaving it PAUSED (pauseReason escalated to MANUAL)"
                }
                return@transaction RestartEgressPrep(preparation = null, currentStatus = ConferenceStreamStatus.PAUSED)
            }

            // Re-derive the plaintext URLs/fingerprints fresh -- see the ORIGINAL resumeStream's
            // own comment (still true here: destination rows still exist while a PAUSED stream
            // references them, deleteDestination refuses otherwise).
            val targetRows =
                (ConferenceStreamTargetTable innerJoin ConferenceStreamDestinationTable)
                    .selectAll()
                    .where { ConferenceStreamTargetTable.streamId eq streamId }
                    .toList()

            // Security-audit MINOR-9 fix -- AUTO-resume only (actorMemberId == null, i.e. the
            // secret-ballot Auto-Resume path: DefaultSecretBallotStreamGuard.resumeStreamsForMeeting /
            // StreamPoller.handlePaused's own orphaned-pause reconciliation). If an operator disabled
            // a destination WHILE this stream sat paused, silently auto-restarting an egress to it
            // would resurrect a stream the operator just took offline behind their back -- the whole
            // point of `enabled=false` is "stop sending here", and an automatic system action must not
            // override that. A MANUAL resumeStream click is a human, in-the-moment decision and is
            // deliberately NOT gated here -- same distinction ConferenceStreamingService.startStream's
            // own `enabled` check already draws for the initial start.
            if (actorMemberId == null && targetRows.any { !it[ConferenceStreamDestinationTable.enabled] }) {
                // Security-audit-round-2 F3 fix -- the log line below already claimed "leaving it PAUSED
                // for manual moderator intervention", but `pauseReason` was left untouched (still
                // SECRET_BALLOT) -- StreamPoller.handlePaused re-enters its SECRET_BALLOT auto-resume
                // branch on EVERY tick as long as this destination stays disabled (its own
                // hasOpenSecretBallot check is false, the ballot already closed), calling this function
                // again, being declined again, forever: an infinite retry loop that never reaches
                // handlePaused's max-duration escalation, holding the room's one-active-stream-per-room
                // slot open indefinitely. One-way escalation to MANUAL -- same D4 semantics
                // ConferenceStreamingService.pauseStream's own manual-pause-while-PAUSING/PAUSED-
                // SECRET_BALLOT branch already establishes -- takes this row OUT of
                // resumeCandidatesForMeeting's/handlePaused's SECRET_BALLOT auto-resume machinery for
                // good: a moderator must now consciously call resumeStream (which is NOT gated on
                // `enabled`, see this branch's own KDoc above) to bring it back, and handlePaused's
                // ordinary maxDurationMinutes ceiling applies again in the meantime instead of being
                // suspended forever.
                ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamId }) {
                    it[status] = ConferenceStreamStatus.PAUSED
                    it[pauseReason] = ConferenceStreamPauseReason.MANUAL
                }
                logger.warn {
                    "restartEgressForStream: at least one destination for stream $streamId is disabled -- " +
                        "auto-resume declined, leaving it PAUSED (pauseReason escalated to MANUAL) for manual " +
                        "moderator intervention"
                }
                return@transaction RestartEgressPrep(preparation = null, currentStatus = ConferenceStreamStatus.PAUSED)
            }

            val urls =
                targetRows.map { tr ->
                    val plaintextUrl = restartRtmpUrl(row = tr, secretBox = secretBox)
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
            ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamId }) { it[status] = ConferenceStreamStatus.STARTING }

            RestartEgressPrep(
                preparation =
                    ResumePreparation(
                        roomName = room[ConferenceRoomTable.livekitRoomName],
                        roomTitle = room[ConferenceRoomTable.title],
                        layout = row[ConferenceStreamTable.layout],
                        latencyMode = row[ConferenceStreamTable.latencyMode],
                        participantIdentity = row[ConferenceStreamTable.participantIdentity],
                        previousRestartCount = row[ConferenceStreamTable.restartCount],
                        urls = urls,
                    ),
                currentStatus = ConferenceStreamStatus.STARTING,
            )
        }

    val toRestart = prep.preparation ?: return prep.currentStatus

    // OUTSIDE the transaction -- never a network call inside an open one, same discipline every
    // other LiveKit call site in this file follows.
    val egressResult =
        runCatching {
            if (toRestart.layout == ConferenceStreamLayout.SINGLE_PARTICIPANT) {
                liveKitEgressClient.startParticipantEgress(
                    roomName = toRestart.roomName,
                    identity = toRestart.participantIdentity!!,
                    latencyMode = toRestart.latencyMode,
                    rtmpUrls = toRestart.urls,
                )
            } else {
                liveKitEgressClient.startRoomCompositeEgress(
                    roomName = toRestart.roomName,
                    layout = toRestart.layout,
                    latencyMode = toRestart.latencyMode,
                    rtmpUrls = toRestart.urls,
                )
            }
        }

    return transaction {
        val now = DbClock.nowLocalDateTime()
        // V1.0 Videokonferenzen, Wave 9, R3 race-fix -- forUpdate() re-read: a concurrent stopStream
        // (or pauseStream) may have moved this row away from STARTING while the LiveKit call above
        // was in flight, OUTSIDE any transaction. Without this re-check, a successful LiveKit call
        // would blindly overwrite whatever stopStream/pauseStream just wrote, resurrecting a stream a
        // moderator (or a racing closeVoting-driven auto-resume) just explicitly ended -- mirrors Tx1's
        // own "respects an already-terminal row" re-check above, applied here for Tx2. The freshly
        // started egress in this abandoned case is deliberately NOT best-effort-stopped here (that
        // would require a network call inside this transaction, which this file's own discipline
        // forbids) -- it is left for StreamPoller's own room-ended/max-duration safety nets, same
        // "best-effort, never blocks the state transition" trade-off this class already accepts
        // elsewhere (see class KDoc "pauseStream/resumeStream/stopStream -- best-effort StopEgress").
        val currentRow =
            ConferenceStreamTable
                .selectAll()
                .where { ConferenceStreamTable.id eq streamId }
                .forUpdate()
                .single()
        if (currentRow[ConferenceStreamTable.status] != ConferenceStreamStatus.STARTING) {
            logger.warn {
                "restartEgressForStream: stream $streamId left STARTING (now ${currentRow[ConferenceStreamTable.status]}) before " +
                    "its LiveKit call returned -- not resurrecting it"
            }
            // Security-audit round-5 fix -- structurally exhaustive fallthrough, REPLACING the
            // growing enumeration of separate `if (statusAtAbandon == ...)` branches rounds 2-4 each
            // added one more case to (MINOR-7 added PAUSED, round-2 F1 added STOPPING/ENDED, round-4
            // R4-2 added PAUSING/FAILED) -- and STILL missed a case: LIVE fell all the way through to
            // a bare `return@transaction statusAtAbandon` with NO write of any kind, silently
            // discarding the freshly-started egress id (round-5 finding). Mirrors
            // [ConferenceStreamingService.startStream]'s own abandoned branch (see that call site's
            // own comment): Step 1, UNCONDITIONAL for every `statusAtAbandon` value -- the freshly
            // returned egress id is recorded on every LiveKit success, inside the SAME `update {}`
            // block that decides the status, so no branch of the `when` below can accidentally skip
            // it. Step 2, conditional -- ONLY the target STATUS is decided per `statusAtAbandon`,
            // entirely separate from step 1's write. The `when` is exhaustive over the full
            // [ConferenceStreamStatus] enum (no `else` catch-all) precisely so a future status value
            // cannot silently fall through unnoticed the way LIVE did here for four audit rounds.
            val statusAtAbandon = currentRow[ConferenceStreamTable.status]
            egressResult.onSuccess { info ->
                ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamId }) {
                    // Step 1 (unconditional) -- see comment above.
                    it[livekitEgressId] = info.egressId
                    // Step 2 (conditional) -- status decision only.
                    when (statusAtAbandon) {
                        ConferenceStreamStatus.PAUSED -> {
                            it[status] = ConferenceStreamStatus.PAUSING
                        }
                        ConferenceStreamStatus.PAUSING -> {
                            // Already PAUSING -- no status change (round-4 R4-2 behaviour, unchanged).
                        }
                        ConferenceStreamStatus.STOPPING, ConferenceStreamStatus.ENDED -> {
                            it[status] = ConferenceStreamStatus.STOPPING
                            it[endedAt] = null
                        }
                        ConferenceStreamStatus.FAILED -> {
                            // Security-audit round-5 fix -- FAILED is now resurrected to STOPPING
                            // exactly like ENDED, not merely id-recorded-and-left-FAILED (round-4
                            // R4-2's behaviour): a row this LiveKit call just proved is ACTUALLY
                            // publishing is no longer "failed to start" in any meaningful sense, and
                            // FAILED sits outside BOTH StreamPoller's NON_TERMINAL_STREAM_STATUSES
                            // sweep and SecretBallotStreamLock's ballot-gate blocklist -- an id
                            // recorded there but never revisited is exactly the kind of unobserved,
                            // unprotected egress this whole fix exists to close. STOPPING routes it
                            // through the normal handleStopping confirm-and-finalize dance instead.
                            it[status] = ConferenceStreamStatus.STOPPING
                            it[endedAt] = null
                        }
                        ConferenceStreamStatus.LIVE -> {
                            // Security-audit round-5 fix -- the case that fell through EVERY prior
                            // round with NO write at all. Deliberately status stays/becomes LIVE, NOT
                            // resurrected to STOPPING/PAUSING: two egresses may now genuinely exist
                            // (the one this row was already observed LIVE with, plus this restart's
                            // own freshly-started one) and there is no reliable way to tell here which
                            // one is actually publishing -- but LIVE is the SAFE choice regardless,
                            // because SecretBallotStreamLock.requireStreamQuiescedForBallot's
                            // fail-closed blocklist already treats LIVE as "publishing, block the
                            // ballot", so nothing is lost on the security side by leaving it LIVE.
                            // Step 1 above already recorded the fresh id (so the NEW egress is not
                            // silently leaked, same as every other case here); this branch adds only a
                            // WARN log with BOTH ids so an operator can investigate the double-egress
                            // situation by hand. Deliberately NOT an automatic stop of the OLD id --
                            // it is not known here whether that old egress still exists or was already
                            // stopped via some other path, and an over-eager automatic stop in a
                            // genuinely exceptional situation like this is a bigger risk than one clear
                            // log line (StopEgress itself is idempotent, but WHICH id to stop is the
                            // actual unknown, not whether stopping twice is safe).
                            logger.warn {
                                "restartEgressForStream: stream $streamId was already LIVE (egress " +
                                    "${currentRow[ConferenceStreamTable.livekitEgressId]}) when this restart's OWN " +
                                    "LiveKit call ALSO succeeded (egress ${info.egressId}) -- two egresses may now " +
                                    "be running; recorded the new id and left status LIVE (still correctly blocks " +
                                    "a secret ballot), manual investigation required"
                            }
                        }
                        ConferenceStreamStatus.STARTING -> {
                            // Structurally unreachable -- this whole branch only runs when
                            // currentRow.status != STARTING (see the enclosing `if` above), so
                            // STARTING can never be `statusAtAbandon`. Listed explicitly (rather than
                            // folded into a silent `else`) so this `when` stays a genuine, exhaustive
                            // enumeration of every ConferenceStreamStatus value instead of a catch-all
                            // that could hide a real, reachable case the way the missing LIVE arm did.
                            logger.warn {
                                "restartEgressForStream: unreachable -- statusAtAbandon was STARTING for stream " +
                                    "$streamId (the enclosing check already requires status != STARTING); egress " +
                                    "id ${info.egressId} recorded, status left untouched"
                            }
                        }
                    }
                }
            }
            // R6-3 fix (cosmetic) -- `egressResult.onSuccess { }` above is a no-op on failure, so on
            // failure NONE of the writes the `when` below describes actually happened; returning that
            // post-write status unconditionally would report a status this call never persisted. All
            // three callers discard this return value and re-read the row fresh regardless, but the
            // honest answer on failure is simply the row's own unchanged statusAtAbandon.
            return@transaction if (egressResult.isFailure) {
                statusAtAbandon
            } else {
                when (statusAtAbandon) {
                    ConferenceStreamStatus.PAUSED -> ConferenceStreamStatus.PAUSING
                    ConferenceStreamStatus.STOPPING, ConferenceStreamStatus.ENDED, ConferenceStreamStatus.FAILED ->
                        ConferenceStreamStatus.STOPPING
                    ConferenceStreamStatus.PAUSING, ConferenceStreamStatus.LIVE, ConferenceStreamStatus.STARTING -> statusAtAbandon
                }
            }
        }
        val roomId = currentRow[ConferenceStreamTable.roomId]
        egressResult
            .onSuccess { info ->
                // See function KDoc "The D3/§6.3 race-fix".
                val roomStillClean = !SecretBallotStreamLock.hasOpenSecretBallot(roomId)
                ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamId }) {
                    it[livekitEgressId] = info.egressId
                    it[restartCount] = toRestart.previousRestartCount + 1
                    if (roomStillClean) {
                        it[status] = ConferenceStreamStatus.LIVE
                        it[pausedAt] = null
                        it[pauseReason] = null
                    } else {
                        it[status] = ConferenceStreamStatus.PAUSING
                        it[pauseReason] = ConferenceStreamPauseReason.SECRET_BALLOT
                    }
                }
            }.onFailure { e ->
                logger.warn {
                    "restartEgressForStream: LiveKit egress start failed for stream $streamId: ${(e as? LiveKitAdminException)?.message}"
                }
                ConferenceStreamTable.update({ ConferenceStreamTable.id eq streamId }) {
                    it[status] = ConferenceStreamStatus.FAILED
                    it[failureReason] = FAILURE_START_FAILED
                    it[restartCount] = toRestart.previousRestartCount + 1
                }
                ConferenceStreamTargetTable.update({ ConferenceStreamTargetTable.streamId eq streamId }) {
                    it[status] = ConferenceStreamTargetStatus.FAILED
                    it[failureReason] = FAILURE_START_FAILED
                }
            }
        AuditLogRecorder.record(
            actorMemberId = actorMemberId,
            actorRole = actorRole,
            entityType = AuditEntityType.CONFERENCE_STREAM,
            entityId = streamId,
            action = AuditAction.UPDATE,
            occurredAt = now,
        )
        ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamId }.single()[ConferenceStreamTable.status]
    }
}

/** [restartEgressForStream]'s own tx1 result -- `preparation == null` means "already handled, do not call LiveKit", see that function's KDoc "Respects an already-terminal row". */
private data class RestartEgressPrep(
    val preparation: ResumePreparation?,
    val currentStatus: ConferenceStreamStatus,
)

/**
 * [restartEgressForStream]'s own plaintext-URL builder -- functionally identical to
 * [ConferenceStreamingService]'s private `buildRtmpUrl` instance method, duplicated (not shared) here
 * purely because THIS function has no [ConferenceStreamingService] instance to call it on (it takes
 * an explicit [secretBox] parameter instead of an instance field). Never logged, never persisted,
 * never returned -- see [SecretBox] KDoc.
 */
private fun restartRtmpUrl(
    row: ResultRow,
    secretBox: SecretBox,
): String {
    val base = row[ConferenceStreamDestinationTable.rtmpUrl].trimEnd('/')
    val destinationId = row[ConferenceStreamDestinationTable.id]
    val key = secretBox.open(sealed = row[ConferenceStreamDestinationTable.streamKeyCiphertext], aad = destinationId.toString())
    return "$base/$key"
}

/**
 * V1.0 Videokonferenzen, Wave 9 "Stream-Pause bei geheimen Abstimmungen" -- server-internal bridge
 * Governance -> Conference, NOT a new [IConferenceStreamingService] method -- exactly the pattern
 * [ConferenceRecordingCoordinator] (Wave 2) already establishes for the analogous
 * `ConferenceService.endRoom -> stop active recordings` bridge. Purely DB-side; the actual
 * `StopEgress` call happens afterwards, OUTSIDE any transaction, in
 * [network.lapis.cloud.server.conference.SecretBallotStreamGuard.quiesceStreamsForMeeting].
 *
 * **Must be called from inside the caller's already-open `transaction {}`**, and as the LAST
 * lock-taking operation of that transaction, exactly like [ConferenceRecordingCoordinator]'s own
 * contract -- see [network.lapis.cloud.server.audit.AuditLogRecorder]'s own "deadlock-avoidance
 * contract" KDoc. `ElectionService.openVoting`/`SystemicConsensusService.freezeOptions`/
 * `.reopenRating` (a later wave step) call this AFTER locking the affected rooms via
 * [SecretBallotStreamLock.lockRooms] and BEFORE their own [AuditLogRecorder.record] call.
 */
object ConferenceStreamPauseCoordinator {
    /**
     * Flips every currently-[ConferenceStreamStatus.STARTING]/[ConferenceStreamStatus.LIVE] stream
     * of every room in [roomIds] to [ConferenceStreamStatus.PAUSING] +
     * `pauseReason = `[ConferenceStreamPauseReason.SECRET_BALLOT], and records one
     * [AuditAction.UPDATE] audit entry per affected stream, attributed to [actorMemberId]/
     * [actorRole] (the member who opened the secret ballot). A stream already in
     * [ConferenceStreamStatus.PAUSING]/[ConferenceStreamStatus.PAUSED]/
     * [ConferenceStreamStatus.STOPPING]/[ConferenceStreamStatus.ENDED]/[ConferenceStreamStatus.FAILED]
     * is left completely untouched -- in particular, an ALREADY-`PAUSING`/`PAUSED` stream (e.g. a
     * moderator manually paused it, or a second concurrent secret ballot already quiesced it) never
     * has its `pause_reason` overwritten back to [ConferenceStreamPauseReason.SECRET_BALLOT] here --
     * see [ConferenceStreamPauseReason] KDoc for the one-way `SECRET_BALLOT -> MANUAL` escalation
     * this deliberately does not run in reverse.
     *
     * Returns the ids of every stream actually flipped -- empty (zero queries beyond the initial
     * lookup) if [roomIds] is empty or none of them has a `STARTING`/`LIVE` stream, which is the
     * common case (see test scenario 1 in the wave's own test plan: "no stream running" must be a
     * true no-op, not merely a cheap one).
     */
    fun markPausingForSecretBallot(
        roomIds: List<Uuid>,
        actorMemberId: Uuid?,
        actorRole: AccountRole?,
        /**
         * Test-only synchronization seam, default no-op -- fires once [candidateIds] has been read,
         * BEFORE the atomic per-row claim loop below runs. Lets a test (see
         * `SecretBallotStreamPauseTest` "R6") deterministically land a concurrent
         * [ConferenceStreamingService.stopStream] in the window this function's own fix closes,
         * without needing a real, timing-dependent thread race. Never set outside tests -- every
         * production call site (`ElectionService.openVoting`, `SystemicConsensusService
         * .freezeOptions`/`.reopenRating`) uses the default.
         */
        onCandidatesSelected: () -> Unit = {},
    ): List<Uuid> {
        if (roomIds.isEmpty()) return emptyList()
        val now = DbClock.nowLocalDateTime()
        // Candidate ids only -- NOT the write decision, see the per-row loop below. A stale read
        // here is harmless: it merely narrows which ids are even worth attempting a claim for.
        val candidateIds =
            ConferenceStreamTable
                .selectAll()
                .where {
                    (ConferenceStreamTable.roomId inList roomIds) and
                        (
                            ConferenceStreamTable.status inList
                                listOf(ConferenceStreamStatus.STARTING, ConferenceStreamStatus.LIVE)
                        )
                }.map { it[ConferenceStreamTable.id] }
        onCandidatesSelected()
        if (candidateIds.isEmpty()) return emptyList()
        // V1.0 Videokonferenzen, Wave 9, review-round R6 race-fix -- atomic conditional claim PER
        // ROW, NOT a SELECT-then-blind-ID-list-UPDATE: the ORIGINAL code gathered affectedIds via
        // the SELECT above, then ran ONE `UPDATE ... WHERE id inList affectedIds` with NO status
        // re-check -- between that SELECT and that UPDATE, a concurrent
        // ConferenceStreamingService.stopStream (which does NOT lock `conference_room` via
        // SecretBallotStreamLock.lockRooms -- see that object's own KDoc "Locking order", stopStream
        // is deliberately outside that serialization) could already have moved a candidate row to
        // ENDED, and the blind UPDATE would stomp it right back to PAUSING. Same race class R4 in
        // restartEgressForStream already fixed the same way (see that function's own KDoc): collapse
        // "check" and "claim" into ONE UPDATE per row whose WHERE clause carries the status
        // condition directly, so the database's own row-level write makes the check-and-claim
        // atomic. A per-row loop (rather than a single `updateReturning()` call) is deliberate --
        // Exposed's RETURNING support has no H2 (this codebase's test dialect) implementation, see
        // `FunctionProvider.returning` KDoc "not supported by all vendors" -- and looping keeps the
        // exact, precise set of ids this call itself actually flipped (never a stream some OTHER,
        // earlier markPausingForSecretBallot call already paused, which a broad post-update
        // `WHERE status = PAUSING AND pause_reason = SECRET_BALLOT` re-SELECT could otherwise
        // wrongly re-attribute to this call's actor/audit entry).
        val affectedIds =
            candidateIds.filter { streamId ->
                ConferenceStreamTable.update({
                    (ConferenceStreamTable.id eq streamId) and
                        (
                            ConferenceStreamTable.status inList
                                listOf(ConferenceStreamStatus.STARTING, ConferenceStreamStatus.LIVE)
                        )
                }) {
                    it[status] = ConferenceStreamStatus.PAUSING
                    it[pauseReason] = ConferenceStreamPauseReason.SECRET_BALLOT
                } == 1
            }
        affectedIds.forEach { streamId ->
            AuditLogRecorder.record(
                actorMemberId = actorMemberId,
                actorRole = actorRole,
                entityType = AuditEntityType.CONFERENCE_STREAM,
                entityId = streamId,
                action = AuditAction.UPDATE,
                occurredAt = now,
            )
        }
        return affectedIds
    }
}
