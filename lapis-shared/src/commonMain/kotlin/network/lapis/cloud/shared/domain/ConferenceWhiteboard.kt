package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 7 "Whiteboard" -- a shared real-time drawing canvas
 * for the main conference room, see [network.lapis.cloud.shared.rpc.IConferenceWhiteboardService]
 * KDoc for the full authorization matrix and design decisions. This file holds the wire shapes
 * only.
 */
@Serializable
data class WhiteboardPointDto(
    val x: Double,
    val y: Double,
)

@Serializable
enum class WhiteboardTool { PEN, ERASER }

/**
 * Wire shape used in THREE places, deliberately unified into one type rather than three near-
 * identical ones: (1) the unreliable data-channel PREVIEW payload (topic
 * `lapis-whiteboard-preview`), (2) the reliable data-channel COMMIT payload (topic
 * `lapis-whiteboard-commit`), (3) [network.lapis.cloud.shared.rpc.IConferenceWhiteboardService.commitStroke]'s
 * RPC input. All three uses need exactly the same fields; giving them three names would only add
 * churn for zero benefit.
 *
 * [points] is ALWAYS the full, cumulative point list of the stroke so far (client's local model),
 * NEVER a delta -- required by the PREVIEW use over an unreliable/unordered channel: a receiver
 * simply renders the newest packet's points, so a lost or reordered packet self-heals on the next
 * one with no gap-tracking/sequence-number machinery needed. The COMMIT/RPC uses reuse the same
 * cumulative shape for consistency, even though they only fire once per stroke.
 *
 * Coordinates are in a FIXED LOGICAL canvas space ([WHITEBOARD_CANVAS_WIDTH] x
 * [WHITEBOARD_CANVAS_HEIGHT]), never raw browser pixel coordinates -- every client scales its own
 * pointer events into this space before ever constructing one of these, and scales back out only
 * when painting onto its own, possibly differently-sized, on-screen `<canvas>`. This is what keeps
 * every participant's strokes aligned regardless of window size, and lets the server-side
 * rasterizer (`network.lapis.cloud.server.conference.WhiteboardRasterizer`) render 1:1 with no
 * separate scaling logic.
 */
@Serializable
data class WhiteboardStrokeWireDto(
    val strokeId: String,
    val tool: WhiteboardTool,
    val color: String,
    val strokeWidth: Double,
    val points: List<WhiteboardPointDto>,
)

/**
 * Server-persisted shape -- adds the two fields [WhiteboardStrokeWireDto] deliberately omits, both
 * filled server-side, NEVER trusted from the client -- see
 * [network.lapis.cloud.shared.rpc.IConferenceWhiteboardService.commitStroke] KDoc "trust boundary"
 * (mirrors [ConferenceChatMessage]'s own `senderMemberId`/`senderDisplayName` pattern -- the wire
 * DTO above carries no author field at all, so there is nothing to accidentally trust).
 */
@Serializable
data class WhiteboardStrokeDto(
    val strokeId: String,
    val authorMemberId: String,
    val authorDisplayName: String,
    val tool: WhiteboardTool,
    val color: String,
    val strokeWidth: Double,
    val points: List<WhiteboardPointDto>,
    val committedAtEpochMs: Long,
)

@Serializable
data class ConferenceWhiteboardStateDto(
    val strokes: List<WhiteboardStrokeDto>,
)

@Serializable
data class ConferenceWhiteboardSaveResultDto(
    val documentId: String,
)

/** Fixed logical canvas size -- see [WhiteboardStrokeWireDto] KDoc. Shared so client and server (`WhiteboardRasterizer`) never drift. */
const val WHITEBOARD_CANVAS_WIDTH = 1600
const val WHITEBOARD_CANVAS_HEIGHT = 1200

/**
 * Fixed 5-color palette, server-enforced allowlist (see
 * `network.lapis.cloud.server.rpc.ConferenceWhiteboardService.validateStroke`) -- matches the
 * scope's "a small set of colors", never free-form hex from the client.
 */
val WHITEBOARD_COLORS = listOf("#1a1a1a", "#e03131", "#2f9e44", "#1971c2", "#f08c00")

/**
 * Structural bounds for a single [WhiteboardStrokeWireDto] -- shared (not duplicated per-platform)
 * because [isStructurallyValid] is the enforcement gate on BOTH sides of this wave's two independent
 * transports: `ConferenceWhiteboardService.validateStroke` (server, the RPC/`commitStroke` path) and
 * `LiveKitRoomSession`'s `RoomEvent.DataReceived` handler (client, the LiveKit data-channel path --
 * see that class KDoc "Whiteboard trust boundary"). The server never observes data-channel traffic at
 * all (see [network.lapis.cloud.shared.rpc.IConferenceWhiteboardService] KDoc "double-write"), so the
 * RECEIVING CLIENT is the only place able to enforce anything on that path -- a value here drifting
 * out of sync between the two call sites would silently reopen exactly the DoS these bounds exist to
 * close (an oversized/out-of-canvas stroke crashing every OTHER participant's rendering loop), so both
 * call sites share these constants and this one function rather than keeping their own copies.
 */
const val WHITEBOARD_MAX_POINTS_PER_STROKE = 2_000
const val WHITEBOARD_MIN_STROKE_WIDTH = 1.0
const val WHITEBOARD_MAX_STROKE_WIDTH = 40.0
const val WHITEBOARD_MAX_STROKE_ID_LENGTH = 100

/**
 * True iff [this] is well-formed enough to decode, store, and render safely -- point count, every
 * coordinate's canvas bounds, `strokeWidth` range, `strokeId` shape, and (for [WhiteboardTool.PEN]
 * only, mirroring the server's own eraser exemption -- eraser rendering always uses opaque white
 * regardless of the wire `color` field, see `ConferenceWhiteboardController` KDoc "Eraser rendering")
 * the fixed [WHITEBOARD_COLORS] palette. Callers decide what an invalid stroke means for them (the
 * server throws `BadRequestException`; a client receiving one over the LiveKit data channel just
 * drops the packet, see [WHITEBOARD_MAX_POINTS_PER_STROKE] KDoc).
 */
fun WhiteboardStrokeWireDto.isStructurallyValid(): Boolean {
    if (strokeId.isBlank() || strokeId.length > WHITEBOARD_MAX_STROKE_ID_LENGTH) return false
    if (points.isEmpty() || points.size > WHITEBOARD_MAX_POINTS_PER_STROKE) return false
    val hasInvalidPoint =
        points.any { p ->
            !isValidCoordinate(value = p.x, max = WHITEBOARD_CANVAS_WIDTH) ||
                !isValidCoordinate(value = p.y, max = WHITEBOARD_CANVAS_HEIGHT)
        }
    if (hasInvalidPoint) return false
    if (strokeWidth.isNaN() || strokeWidth !in WHITEBOARD_MIN_STROKE_WIDTH..WHITEBOARD_MAX_STROKE_WIDTH) return false
    if (tool == WhiteboardTool.PEN && color !in WHITEBOARD_COLORS) return false
    return true
}

private fun isValidCoordinate(
    value: Double,
    max: Int,
): Boolean = !value.isNaN() && value.isFinite() && value in 0.0..max.toDouble()
