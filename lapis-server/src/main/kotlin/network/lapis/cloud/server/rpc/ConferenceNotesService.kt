package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceNotesState
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.routes.archiveGeneratedBytes
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.ConferenceNotesSaveResultDto
import network.lapis.cloud.shared.domain.ConferenceNotesStateDto
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.NOTES_MAX_BLOCK_ID_LENGTH
import network.lapis.cloud.shared.domain.NOTES_MAX_CONTENT_LENGTH
import network.lapis.cloud.shared.domain.NoteBlockCommitResultDto
import network.lapis.cloud.shared.domain.NoteBlockCreateWireDto
import network.lapis.cloud.shared.domain.NoteBlockDto
import network.lapis.cloud.shared.domain.NoteBlockEditWireDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IConferenceNotesService
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

/** New block, deliberately low frequency -- a new agenda item/paragraph, nowhere near a whiteboard stroke's own commit cadence. */
private const val DEFAULT_CREATE_RATE_MAX = 30

/** On-blur/explicit-"Speichern"-button cadence across possibly several blocks per minute -- still far below Whiteboard's 120/min per-stroke-commit budget. */
private const val DEFAULT_EDIT_RATE_MAX = 60

/** Mirrors [ConferenceWhiteboardService]'s own `DEFAULT_CLEAR_RATE_MAX` conservative posture for a destructive-ish action. */
private const val DEFAULT_DELETE_RATE_MAX = 20
private const val DEFAULT_SAVE_RATE_MAX = 10

// Content-length/blockId bounds live in [network.lapis.cloud.shared.domain] (NOTES_MAX_CONTENT_LENGTH
// and friends), NOT as local private consts here -- see that file's KDoc "Structural bounds" for why:
// the SAME numbers must also gate LiveKitRoomSession's data-channel receive path client-side (this
// server never observes that traffic at all), and a drifted local copy here would silently reopen the
// DoS those bounds exist to close.

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 8 "Geteilte Notizen" -- see [IConferenceNotesService]
 * KDoc for the full authorization matrix and design decisions.
 *
 * ## Authorization re-derivation, never a cached role
 *
 * [requireOpenParticipation]/[requireModeratorOrPrivileged] are deliberately DUPLICATED here (not
 * extracted into a shared helper), mirroring [ConferenceWhiteboardService] KDoc "Authorization
 * re-derivation, never a cached role"'s own identical reasoning.
 *
 * ## TOCTOU across the DB-tx / in-memory boundary
 *
 * [createBlock]/[commitBlockEdit] re-verify the room is still open via [requireRoomStillOpen] in a
 * SECOND, final transaction immediately before touching [ConferenceNotesState] -- same "re-verify in
 * final tx" shape as [ConferenceWhiteboardService.commitStroke]'s own major review fix, same
 * reasoning: `ConferenceService.endRoom`/`.reconcileRoomIfDue` clear a room's notes state exactly
 * ONCE (guarded by `endedAt` transitioning null -> non-null), so a create/edit landing after that
 * one-shot clear would leak into the map forever.
 */
class ConferenceNotesService(
    private val call: ApplicationCall,
    private val documentStorageRoot: File,
    private val notesState: ConferenceNotesState,
    private val config: ConferenceConfig = ConferenceConfig.load(),
    private val readRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_READ_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
    private val createRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_CREATE_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
    private val editRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_EDIT_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
    private val deleteRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_DELETE_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
    private val saveRateLimiter: FederationInboxRateLimiter =
        FederationInboxRateLimiter(maxRequests = DEFAULT_SAVE_RATE_MAX, window = DEFAULT_ACTION_RATE_WINDOW),
) : IConferenceNotesService {
    override suspend fun getNotesState(roomId: String): ConferenceNotesStateDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = readRateLimiter, memberId = current.memberId)
        val roomUuid = roomId.toNotesUuid()
        transaction {
            requireRoomExists(roomUuid)
            requireOpenParticipation(roomId = roomUuid, memberId = current.memberId)
        }
        return ConferenceNotesStateDto(blocks = notesState.snapshot(roomUuid))
    }

    override suspend fun createBlock(
        roomId: String,
        block: NoteBlockCreateWireDto,
    ): NoteBlockDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = createRateLimiter, memberId = current.memberId)
        val roomUuid = roomId.toNotesUuid()
        validateBlockId(block.blockId)
        validateContent(block.content)

        val displayName =
            transaction {
                requireRoomExists(roomUuid)
                requireOpenParticipation(roomId = roomUuid, memberId = current.memberId)
                MemberTable.selectAll().where { MemberTable.id eq current.memberId }.single()[MemberTable.displayName]
            }

        val dto =
            NoteBlockDto(
                id = block.blockId,
                content = block.content,
                position = block.position,
                version = 1,
                lastEditedByMemberId = current.memberId.toString(),
                lastEditedByDisplayName = displayName,
                lastEditedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            )
        // TOCTOU re-check -- see class KDoc "TOCTOU across the DB-tx / in-memory boundary". A create
        // landing after ConferenceService.endRoom/.reconcileRoomIfDue's one-shot notesState.clear(id)
        // but before this re-check would resurrect content into an already-ended room's bucket
        // FOREVER, until process restart.
        transaction { requireRoomStillOpen(roomUuid) }
        return when (val r = notesState.tryCreate(roomId = roomUuid, block = dto)) {
            is ConferenceNotesState.CreateResult.Ok -> r.block
            ConferenceNotesState.CreateResult.RoomFull ->
                throw ConflictException(
                    "Notizen für diesen Raum sind voll -- als Dokument speichern und nicht mehr benötigte Blöcke entfernen.",
                )
        }
    }

    override suspend fun commitBlockEdit(
        roomId: String,
        edit: NoteBlockEditWireDto,
    ): NoteBlockCommitResultDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = editRateLimiter, memberId = current.memberId)
        val roomUuid = roomId.toNotesUuid()
        validateBlockId(edit.blockId)
        validateContent(edit.content)

        val displayName =
            transaction {
                requireRoomExists(roomUuid)
                requireOpenParticipation(roomId = roomUuid, memberId = current.memberId)
                MemberTable.selectAll().where { MemberTable.id eq current.memberId }.single()[MemberTable.displayName]
            }
        // Same TOCTOU reasoning as createBlock.
        transaction { requireRoomStillOpen(roomUuid) }
        return when (
            val r =
                notesState.tryEdit(
                    roomId = roomUuid,
                    blockId = edit.blockId,
                    baseVersion = edit.baseVersion,
                    newContent = edit.content,
                    editorMemberId = current.memberId.toString(),
                    editorDisplayName = displayName,
                    nowEpochMs = Clock.System.now().toEpochMilliseconds(),
                )
        ) {
            is ConferenceNotesState.EditResult.Accepted -> NoteBlockCommitResultDto(accepted = true, block = r.block)
            is ConferenceNotesState.EditResult.StaleVersion -> NoteBlockCommitResultDto(accepted = false, block = r.current)
            ConferenceNotesState.EditResult.NotFound -> NoteBlockCommitResultDto(accepted = false, block = null)
        }
    }

    override suspend fun deleteBlock(
        roomId: String,
        blockId: String,
    ) {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = deleteRateLimiter, memberId = current.memberId)
        val roomUuid = roomId.toNotesUuid()
        val row =
            transaction {
                val r = requireRoomExists(roomUuid)
                requireOpenParticipation(roomId = roomUuid, memberId = current.memberId)
                r
            }
        val canModerate = row[ConferenceRoomTable.createdByMemberId] == current.memberId || current.isPrivileged
        // Not leak-critical (removing from an already-cleared map is inherently safe) but included
        // for consistent, explicit "room already ended" UX rather than a silent no-op.
        transaction { requireRoomStillOpen(roomUuid) }
        when (
            notesState.tryDelete(
                roomId = roomUuid,
                blockId = blockId,
                callerMemberId = current.memberId.toString(),
                callerCanModerate = canModerate,
            )
        ) {
            ConferenceNotesState.DeleteResult.REMOVED, ConferenceNotesState.DeleteResult.NOT_FOUND -> Unit
            ConferenceNotesState.DeleteResult.FORBIDDEN ->
                throw ForbiddenException("Nur der zuletzt bearbeitende Teilnehmer oder ein Moderator kann diesen Block entfernen")
        }
    }

    override suspend fun saveAsDocument(
        roomId: String,
        accessLevel: DocumentAccessLevel,
    ): ConferenceNotesSaveResultDto {
        val current = resolveCurrentMember(call)
        requireConferenceEnabled()
        requireWithinRate(limiter = saveRateLimiter, memberId = current.memberId)
        val roomUuid = roomId.toNotesUuid()
        transaction {
            requireRoomExists(roomUuid)
            requireOpenParticipation(roomId = roomUuid, memberId = current.memberId)
        }
        // Already position-sorted, see ConferenceNotesState.snapshot.
        val blocks = notesState.snapshot(roomUuid)
        if (blocks.isEmpty()) throw ConflictException("Notizen sind leer -- nichts zu speichern.")

        val bytes = renderNotesAsMarkdown(roomId = roomUuid, blocks = blocks).toByteArray(Charsets.UTF_8)
        val documentId =
            archiveGeneratedBytes(
                storageRoot = documentStorageRoot,
                folderName = "Notizen",
                fileName = "notizen-${Uuid.random()}.md",
                title = "Geteilte Notizen $roomUuid",
                bytes = bytes,
                mimeType = "text/markdown",
                uploadedBy = current.memberId,
                accessLevel = accessLevel,
                changeNote = "Automatisch generiert (V1.0 Wave 8 Geteilte Notizen)",
            )
        return ConferenceNotesSaveResultDto(documentId = documentId.toString())
    }

    // ── internal helpers ──────────────────────────────────────────────────

    private fun renderNotesAsMarkdown(
        roomId: Uuid,
        blocks: List<NoteBlockDto>,
    ): String =
        buildString {
            appendLine("# Geteilte Notizen -- Konferenzraum $roomId")
            appendLine()
            blocks.forEach { b ->
                appendLine(b.content)
                appendLine()
                appendLine("_-- zuletzt bearbeitet von ${b.lastEditedByDisplayName}_")
                appendLine()
                appendLine("---")
                appendLine()
            }
        }

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

    /** See [ConferenceWhiteboardService.requireRoomStillOpen] KDoc -- identical shape, identical reasoning, deliberately duplicated. */
    private fun requireRoomStillOpen(roomId: Uuid) {
        val row =
            ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.singleOrNull()
                ?: throw NotFoundException("Conference room $roomId not found")
        if (row[ConferenceRoomTable.endedAt] != null) {
            throw ConflictException("Conference room $roomId has already ended")
        }
    }

    /** "Actual current participant" -- see class KDoc and [IConferenceNotesService] KDoc. */
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

    private fun validateBlockId(id: String) {
        if (id.isBlank() || id.length > NOTES_MAX_BLOCK_ID_LENGTH) {
            throw BadRequestException("blockId must be non-blank and at most $NOTES_MAX_BLOCK_ID_LENGTH characters")
        }
    }

    private fun validateContent(content: String) {
        if (content.isBlank()) throw BadRequestException("content must not be blank")
        if (content.length > NOTES_MAX_CONTENT_LENGTH) {
            throw BadRequestException("content must be at most $NOTES_MAX_CONTENT_LENGTH characters")
        }
    }

    private fun String.toNotesUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
}
