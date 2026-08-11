package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceWhiteboardState
import network.lapis.cloud.server.conference.WhiteboardRasterizer
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.routes.archiveGeneratedBytes
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.ConferenceWhiteboardSaveResultDto
import network.lapis.cloud.shared.domain.ConferenceWhiteboardStateDto
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.WHITEBOARD_CANVAS_HEIGHT
import network.lapis.cloud.shared.domain.WHITEBOARD_CANVAS_WIDTH
import network.lapis.cloud.shared.domain.WHITEBOARD_COLORS
import network.lapis.cloud.shared.domain.WHITEBOARD_MAX_POINTS_PER_STROKE
import network.lapis.cloud.shared.domain.WHITEBOARD_MAX_STROKE_ID_LENGTH
import network.lapis.cloud.shared.domain.WHITEBOARD_MAX_STROKE_WIDTH
import network.lapis.cloud.shared.domain.WHITEBOARD_MIN_STROKE_WIDTH
import network.lapis.cloud.shared.domain.WhiteboardStrokeDto
import network.lapis.cloud.shared.domain.WhiteboardStrokeWireDto
import network.lapis.cloud.shared.domain.WhiteboardTool
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IConferenceWhiteboardService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val DEFAULT_ACTION_RATE_WINDOW = 1.minutes
private const val DEFAULT_READ_RATE_MAX = 30

/** ~2/sec sustained -- one [ConferenceWhiteboardService.commitStroke] call per FINISHED stroke, not per pointer-move (preview never touches the RPC layer, see [IConferenceWhiteboardService] KDoc "double-write"). */
private const val DEFAULT_COMMIT_RATE_MAX = 120
private const val DEFAULT_CLEAR_RATE_MAX = 10
private const val DEFAULT_SAVE_RATE_MAX = 10

// Point-count/coordinate/width/color/strokeId bounds live in [network.lapis.cloud.shared.domain]
// (`WHITEBOARD_MAX_POINTS_PER_STROKE` and friends), NOT as local private consts here -- see that
// file's KDoc "Structural bounds for a single WhiteboardStrokeWireDto" for why: the SAME numbers
// must also gate `LiveKitRoomSession`'s data-channel receive path client-side (this server never
// observes that traffic at all), and a drifted local copy here would silently reopen the DoS those
// bounds exist to close.

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 7 "Whiteboard" -- see [IConferenceWhiteboardService]
 * KDoc for the full authorization matrix and design decisions.
 *
 * ## Authorization re-derivation, never a cached role
 *
 * [requireOpenParticipation]/[requireModeratorOrPrivileged] are deliberately DUPLICATED here (not
 * extracted into a shared helper), mirroring [ConferenceBreakoutService] KDoc "Authorization
 * re-derivation, never a cached role"'s own identical reasoning.
 *
 * [requireOpenParticipation] is the STRICTER "open participation" gate (`leftAt IS NULL`), NOT
 * [IConferenceBreakoutService.getMyBreakoutAssignment]'s own looser "ever participated" gate --
 * see [IConferenceWhiteboardService.getWhiteboardState] KDoc for why the looser gate would be wrong
 * for live collaboration state.
 *
 * ## TOCTOU across the DB-tx / in-memory boundary
 *
 * [commitStroke] re-verifies the room is still open via [requireRoomStillOpen] in a SECOND, final
 * transaction immediately before calling [ConferenceWhiteboardState.tryCommit] -- see that call
 * site's own comment. Review fix (major): the FIRST check (inside the `displayName` transaction)
 * can be stale by the time the in-memory `tryCommit` actually runs, and because
 * `ConferenceService.endRoom`/`.reconcileRoomIfDue` clear a room's whiteboard state exactly ONCE
 * (guarded by `endedAt` transitioning null -> non-null), a stroke landing after that one-shot clear
 * would leak into the map forever.
 */
class ConferenceWhiteboardService(
    private val call: ApplicationCall,
    private val documentStorageRoot: File,
    private val whiteboardState: ConferenceWhiteboardState,
    private val config: ConferenceConfig = ConferenceConfig.load(),
    private val readRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_READ_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
    private val commitRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_COMMIT_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
    private val clearRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_CLEAR_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
    private val saveRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_SAVE_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
) : IConferenceWhiteboardService {
    override suspend fun getWhiteboardState(roomId: String): ConferenceWhiteboardStateDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(readRateLimiter, current.memberId)
        val roomUuid = roomId.toWhiteboardUuid()
        transaction {
            requireRoomExists(roomUuid)
            requireOpenParticipation(roomUuid, current.memberId)
        }
        return ConferenceWhiteboardStateDto(strokes = whiteboardState.snapshot(roomUuid))
    }

    override suspend fun commitStroke(
        roomId: String,
        stroke: WhiteboardStrokeWireDto,
    ): WhiteboardStrokeDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(commitRateLimiter, current.memberId)
        val roomUuid = roomId.toWhiteboardUuid()
        validateStroke(stroke)

        val displayName =
            transaction {
                requireRoomExists(roomUuid)
                requireOpenParticipation(roomUuid, current.memberId)
                MemberTable.selectAll().where { MemberTable.id eq current.memberId }.single()[MemberTable.displayName]
            }

        val dto =
            WhiteboardStrokeDto(
                strokeId = stroke.strokeId,
                authorMemberId = current.memberId.toString(),
                authorDisplayName = displayName,
                tool = stroke.tool,
                color = stroke.color,
                strokeWidth = stroke.strokeWidth,
                points = stroke.points,
                committedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            )
        // Review fix (major, TOCTOU): re-verify the room is still open in a FRESH, final transaction
        // immediately before the in-memory `tryCommit` below -- same "re-verify in final tx" shape as
        // Wave 5's `joinRoom` TOCTOU fix (see that function's own KDoc). The `requireOpenParticipation`
        // check above can be stale by the time this point is reached: `ConferenceService.endRoom`/
        // `.reconcileRoomIfDue` stamp `endedAt` and then fire a ONE-SHOT `whiteboardState.clear(id)`
        // (guarded by `endedAt` transitioning null -> non-null, never repeated for that room
        // afterward -- see `ConferenceWhiteboardState.clear` KDoc), so a `tryCommit` landing between
        // that clear and this point would resurrect a stroke into an already-ended room's bucket
        // FOREVER, until process restart. Re-checking here shrinks that window from "this whole RPC's
        // processing time so far (validation, a DB round-trip for the display name)" down to the
        // handful of CPU instructions between this check and the `tryCommit` call below -- narrow
        // enough that closing it further (e.g. a cross-request lock shared with `endRoom`) is not
        // worth the added complexity for a plain, side-effect-free in-memory structure at
        // "Kleinsitzung" scale (see `ConferenceWhiteboardState` class KDoc).
        transaction { requireRoomStillOpen(roomUuid) }
        if (!whiteboardState.tryCommit(roomUuid, dto)) {
            throw ConflictException("Whiteboard für diesen Raum ist voll -- Board leeren oder als Dokument speichern.")
        }
        return dto
    }

    override suspend fun clearBoard(roomId: String) {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(clearRateLimiter, current.memberId)
        val roomUuid = roomId.toWhiteboardUuid()
        transaction {
            val row = requireRoomExists(roomUuid)
            requireModeratorOrPrivileged(row, current)
        }
        whiteboardState.clear(roomUuid)
    }

    override suspend fun saveAsDocument(
        roomId: String,
        accessLevel: DocumentAccessLevel,
    ): ConferenceWhiteboardSaveResultDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(saveRateLimiter, current.memberId)
        val roomUuid = roomId.toWhiteboardUuid()
        transaction {
            requireRoomExists(roomUuid)
            requireOpenParticipation(roomUuid, current.memberId)
        }
        val strokes = whiteboardState.snapshot(roomUuid)
        if (strokes.isEmpty()) throw ConflictException("Whiteboard ist leer -- nichts zu speichern.")

        val pngBytes = WhiteboardRasterizer.render(strokes)
        val documentId =
            archiveGeneratedBytes(
                storageRoot = documentStorageRoot,
                folderName = "Whiteboards",
                fileName = "whiteboard-${Uuid.random()}.png",
                title = "Whiteboard $roomUuid",
                bytes = pngBytes,
                mimeType = "image/png",
                uploadedBy = current.memberId,
                accessLevel = accessLevel,
                changeNote = "Automatisch generiert (V1.0 Wave 7 Whiteboard)",
            )
        return ConferenceWhiteboardSaveResultDto(documentId = documentId.toString())
    }

    // ── internal helpers ──────────────────────────────────────────────────

    private fun requireConferenceEnabled() {
        if (!config.enabled) {
            throw ConflictException(
                "Videokonferenzen is not configured on this server (LAPIS_LIVEKIT_URL/_API_KEY/_API_SECRET " +
                    "unset) -- see ConferenceConfig KDoc",
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

    private fun requireRoomExists(roomId: Uuid): ResultRow =
        ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.singleOrNull()
            ?: throw NotFoundException("Conference room $roomId not found")

    /** See [commitStroke]'s own call-site comment "TOCTOU" -- a narrow, final re-check that the room has not ended between the top-of-function `requireOpenParticipation` check and the in-memory `tryCommit` call. Mirrors `ConferenceService.joinRoom`'s own re-check shape (`endedAt != null` -> `ConflictException`), NOT `requireRoomExists`'s `NotFoundException` -- a room that existed a moment ago and has since ended is a conflict, not a 404. */
    private fun requireRoomStillOpen(roomId: Uuid) {
        val row =
            ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.singleOrNull()
                ?: throw NotFoundException("Conference room $roomId not found")
        if (row[ConferenceRoomTable.endedAt] != null) {
            throw ConflictException("Conference room $roomId has already ended")
        }
    }

    /** "Actual current participant" -- STRICTER than [network.lapis.cloud.shared.rpc.IConferenceBreakoutService.getMyBreakoutAssignment]'s own "ever participated" gate, mirrors [ConferenceBreakoutService.assignParticipants]'s inline `hasOpenParticipation` check instead. See class KDoc and [IConferenceWhiteboardService] KDoc. */
    private fun requireOpenParticipation(
        roomId: Uuid,
        memberId: Uuid,
    ) {
        val hasOpen =
            ConferenceParticipationTable
                .selectAll()
                .where {
                    (ConferenceParticipationTable.roomId eq roomId) and
                        (ConferenceParticipationTable.memberId eq memberId) and
                        ConferenceParticipationTable.leftAt.isNull()
                }.limit(1)
                .any()
        if (!hasOpen) throw ForbiddenException("Caller does not currently hold an open participation in room $roomId")
    }

    /** See class KDoc "Authorization re-derivation, never a cached role" -- deliberately duplicated, not shared with [ConferenceService]/[ConferenceBreakoutService]. Deliberately does NOT require an open participation -- mirrors `endRoom`'s own "moderator persists regardless of live connection state". */
    private fun requireModeratorOrPrivileged(
        row: ResultRow,
        current: CurrentMember,
    ) {
        val isCreator = row[ConferenceRoomTable.createdByMemberId] == current.memberId
        if (!isCreator && !current.isPrivileged) throw ForbiddenException()
    }

    private fun validateStroke(stroke: WhiteboardStrokeWireDto) {
        if (stroke.strokeId.isBlank() || stroke.strokeId.length > WHITEBOARD_MAX_STROKE_ID_LENGTH) {
            throw BadRequestException("strokeId must be non-blank and at most $WHITEBOARD_MAX_STROKE_ID_LENGTH characters")
        }
        if (stroke.points.isEmpty() || stroke.points.size > WHITEBOARD_MAX_POINTS_PER_STROKE) {
            throw BadRequestException("points must have between 1 and $WHITEBOARD_MAX_POINTS_PER_STROKE entries")
        }
        stroke.points.forEach { p ->
            val validX = !p.x.isNaN() && p.x.isFinite() && p.x in 0.0..WHITEBOARD_CANVAS_WIDTH.toDouble()
            val validY = !p.y.isNaN() && p.y.isFinite() && p.y in 0.0..WHITEBOARD_CANVAS_HEIGHT.toDouble()
            if (!validX || !validY) throw BadRequestException("point coordinates out of bounds")
        }
        if (stroke.strokeWidth.isNaN() || stroke.strokeWidth !in WHITEBOARD_MIN_STROKE_WIDTH..WHITEBOARD_MAX_STROKE_WIDTH) {
            throw BadRequestException("strokeWidth must be between $WHITEBOARD_MIN_STROKE_WIDTH and $WHITEBOARD_MAX_STROKE_WIDTH")
        }
        if (stroke.tool == WhiteboardTool.PEN && stroke.color !in WHITEBOARD_COLORS) {
            throw BadRequestException("color must be one of the allowed palette")
        }
    }

    private fun String.toWhiteboardUuid(): Uuid =
        runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
}
