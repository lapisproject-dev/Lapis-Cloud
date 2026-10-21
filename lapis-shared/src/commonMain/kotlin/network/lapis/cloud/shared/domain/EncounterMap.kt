package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Welle V1.9.166 (encounter room stage 3, wave 1) -- the grid map. Shared between server and client: the dimensions, the room templates
 * (walls and objects only -- **free of any personal data**), the shortest-path search, the DTOs of the five map RPCs and the strict
 * parser of the data-channel packet.
 *
 * **Privacy by construction**: a position exists only in the memory of the server (`EncounterMapState`) and in the memory of a tab. No DTO
 * here carries a time, a history or a home server. Architecture and threat model: `docs/architecture/encounter-space.adoc`.
 */
const val ENCOUNTER_MAP_COLUMNS = 12
const val ENCOUNTER_MAP_ROWS = 8

/** At most this many people stand on the map of one session. */
const val ENCOUNTER_MAP_MAX_WALKERS = 25

/** Every room template offers at least this many walkable tiles. */
const val ENCOUNTER_MAP_MIN_FREE_TILES = 40

/** Data-channel topic of the position packets. Sent ONLY by the server through `sendDataTo`, only to members. */
const val ENCOUNTER_MAP_TOPIC = "lapis-encounter-map"

/** Largest packet the client accepts and the server sends (a larger delta becomes a reset). */
const val ENCOUNTER_MAP_MAX_PAYLOAD_BYTES = 4096

/** The client sends at most one step per this interval. */
const val ENCOUNTER_MAP_CLIENT_STEP_MS = 150

/** The anchor buttons ("Zum Eingang" etc.) wait this long after a jump; the server allows one jump per second. */
const val ENCOUNTER_MAP_ANCHOR_COOLDOWN_MS = 1_000L

/** A click-to-walk path is at most this long. */
const val ENCOUNTER_MAP_PATH_MAX_STEPS = 20

/** "In your vicinity" = Chebyshev distance up to this. */
const val ENCOUNTER_MAP_NEAR_RADIUS = 2

/** The list "in your vicinity" names at most this many people, the rest is counted. */
const val ENCOUNTER_MAP_NEAR_NAMES_MAX = 8

/** Initials on the wire are at most this long. */
const val ENCOUNTER_MAP_INITIALS_MAX = 3

/** Wire format version (field `v`). */
const val ENCOUNTER_MAP_WIRE_VERSION = 1

private const val ENCOUNTER_MAP_PACKET_LIST_MAX = 64

/** Facing and step direction in one. Append only. */
@Serializable
enum class EncounterMapFacing { NORTH, EAST, SOUTH, WEST }

/** The three teleport anchors. Append only. */
@Serializable
enum class EncounterMapAnchor { ENTRANCE, PULPIT, CENTER }

/** The result of a step. Append only. */
@Serializable
enum class EncounterMapMove { MOVED, BLOCKED, THROTTLED }

/** The caller's own position. [onMap] `false` = not on the map ([x]/[y] are -1). */
@Serializable
data class EncounterMapSelfDto(
    val onMap: Boolean,
    val x: Int = -1,
    val y: Int = -1,
    val facing: EncounterMapFacing = EncounterMapFacing.SOUTH,
    val move: EncounterMapMove = EncounterMapMove.MOVED,
)

/** One person on the map. No name: the client resolves the name through the presence list it already holds. */
@Serializable
data class EncounterMapPositionDto(
    val memberId: String,
    val x: Int,
    val y: Int,
    val facing: EncounterMapFacing,
    val initials: String,
)

@Serializable
data class EncounterMapSnapshotDto(
    val epoch: String,
    val seq: Long,
    val positions: List<EncounterMapPositionDto>,
    val self: EncounterMapSelfDto,
)

enum class EncounterMapCell { FLOOR, WALL, OBJECT }

fun encounterMapIndex(
    x: Int,
    y: Int,
): Int = y * ENCOUNTER_MAP_COLUMNS + x

/** A room template of the map: 12 x 8 cells, `#` wall, `o` object, `.` floor, plus the three teleport anchors (tile indices). */
class EncounterMapTemplate internal constructor(
    val layout: EncounterRoomLayout,
    rows: List<String>,
    val anchors: Map<EncounterMapAnchor, Int>,
) {
    private val cells: List<EncounterMapCell>

    init {
        require(rows.size == ENCOUNTER_MAP_ROWS && rows.all { it.length == ENCOUNTER_MAP_COLUMNS }) { "template must be 12 x 8" }
        cells =
            rows.flatMap { row ->
                row.map {
                    when (it) {
                        '#' -> EncounterMapCell.WALL
                        'o' -> EncounterMapCell.OBJECT
                        else -> EncounterMapCell.FLOOR
                    }
                }
            }
    }

    fun cell(
        x: Int,
        y: Int,
    ): EncounterMapCell =
        if (x !in 0 until ENCOUNTER_MAP_COLUMNS || y !in 0 until ENCOUNTER_MAP_ROWS) {
            EncounterMapCell.WALL
        } else {
            cells[encounterMapIndex(x = x, y = y)]
        }

    fun walkable(
        x: Int,
        y: Int,
    ): Boolean = cell(x = x, y = y) == EncounterMapCell.FLOOR

    fun walkable(index: Int): Boolean = index in cells.indices && cells[index] == EncounterMapCell.FLOOR

    /** The tile next to [index] in direction [dir]; `null` = the edge of the map. The tile itself may be a wall. */
    fun neighbor(
        index: Int,
        dir: EncounterMapFacing,
    ): Int? {
        val x = index % ENCOUNTER_MAP_COLUMNS
        val y = index / ENCOUNTER_MAP_COLUMNS
        val nx = x + dx(dir)
        val ny = y + dy(dir)
        if (nx !in 0 until ENCOUNTER_MAP_COLUMNS || ny !in 0 until ENCOUNTER_MAP_ROWS) return null
        return encounterMapIndex(x = nx, y = ny)
    }

    fun freeTiles(): Int = cells.count { it == EncounterMapCell.FLOOR }

    private fun dx(dir: EncounterMapFacing): Int =
        when (dir) {
            EncounterMapFacing.EAST -> 1
            EncounterMapFacing.WEST -> -1
            else -> 0
        }

    private fun dy(dir: EncounterMapFacing): Int =
        when (dir) {
            EncounterMapFacing.SOUTH -> 1
            EncounterMapFacing.NORTH -> -1
            else -> 0
        }
}

/** The fixed templates. They hang on the room layout (which cannot change while the room is open). Pure, no personal data. */
object EncounterMapTemplates {
    private fun anchors(
        entrance: Pair<Int, Int>,
        pulpit: Pair<Int, Int>,
        center: Pair<Int, Int>,
    ) = mapOf(
        EncounterMapAnchor.ENTRANCE to encounterMapIndex(x = entrance.first, y = entrance.second),
        EncounterMapAnchor.PULPIT to encounterMapIndex(x = pulpit.first, y = pulpit.second),
        EncounterMapAnchor.CENTER to encounterMapIndex(x = center.first, y = center.second),
    )

    private val hall =
        EncounterMapTemplate(
            layout = EncounterRoomLayout.HALL,
            rows =
                listOf(
                    "############",
                    "#..oooooo..#",
                    "#..........#",
                    "#.oo....oo.#",
                    "#..........#",
                    "#.oo....oo.#",
                    "#..........#",
                    "####....####",
                ),
            anchors = anchors(entrance = 5 to 7, pulpit = 5 to 2, center = 5 to 4),
        )

    private val foyer =
        EncounterMapTemplate(
            layout = EncounterRoomLayout.FOYER,
            rows =
                listOf(
                    "############",
                    "#..........#",
                    "#.oo....oo.#",
                    "#.oo....oo.#",
                    "#..........#",
                    "#.oo....oo.#",
                    "#.oo....oo.#",
                    "####....####",
                ),
            anchors = anchors(entrance = 5 to 7, pulpit = 5 to 1, center = 5 to 4),
        )

    private val smallGroup =
        EncounterMapTemplate(
            layout = EncounterRoomLayout.SMALL_GROUP,
            rows =
                listOf(
                    "############",
                    "#..........#",
                    "#.oooooooo.#",
                    "#..........#",
                    "#.oooooooo.#",
                    "#..........#",
                    "#..........#",
                    "####....####",
                ),
            anchors = anchors(entrance = 5 to 7, pulpit = 5 to 1, center = 5 to 3),
        )

    /** `null` for the nave (the church profile has no map). */
    fun of(layout: EncounterRoomLayout): EncounterMapTemplate? =
        when (layout) {
            EncounterRoomLayout.HALL -> hall
            EncounterRoomLayout.FOYER -> foyer
            EncounterRoomLayout.SMALL_GROUP -> smallGroup
            EncounterRoomLayout.NAVE -> null
        }
}

private val ENCOUNTER_MAP_DIRS =
    listOf(EncounterMapFacing.NORTH, EncounterMapFacing.EAST, EncounterMapFacing.SOUTH, EncounterMapFacing.WEST)

/** The nearest unoccupied walkable tile (breadth first from [from], deterministic order N, E, S, W); `null` if none is reachable. */
fun encounterMapNearestFree(
    t: EncounterMapTemplate,
    occupied: Set<Int>,
    from: Int,
): Int? {
    if (from !in 0 until ENCOUNTER_MAP_COLUMNS * ENCOUNTER_MAP_ROWS) return null
    val seen = HashSet<Int>()
    val queue = ArrayDeque<Int>()
    queue.addLast(from)
    seen += from
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (t.walkable(current) && current !in occupied) return current
        for (dir in ENCOUNTER_MAP_DIRS) {
            val next = t.neighbor(index = current, dir = dir) ?: continue
            // Walls block the search too, but the start tile may be an odd one (an anchor is always walkable).
            if (next in seen || !t.walkable(next)) continue
            seen += next
            queue.addLast(next)
        }
    }
    return null
}

/**
 * The shortest sequence of single steps from [from] to [to] over free, walkable tiles (people in [occupied] block, [from] itself does not);
 * `null` if [to] is not walkable or occupied, unreachable, or farther than [maxSteps].
 */
fun encounterMapPath(
    t: EncounterMapTemplate,
    occupied: Set<Int>,
    from: Int,
    to: Int,
    maxSteps: Int,
): List<EncounterMapFacing>? {
    if (!t.walkable(from) || !t.walkable(to) || (to in occupied && to != from)) return null
    if (from == to) return emptyList()
    val cameFrom = HashMap<Int, Pair<Int, EncounterMapFacing>>()
    val depth = HashMap<Int, Int>()
    val queue = ArrayDeque<Int>()
    queue.addLast(from)
    depth[from] = 0
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        val d = depth.getValue(current)
        if (d >= maxSteps) continue
        for (dir in ENCOUNTER_MAP_DIRS) {
            val next = t.neighbor(index = current, dir = dir) ?: continue
            if (next in depth || !t.walkable(next) || next in occupied) continue
            depth[next] = d + 1
            cameFrom[next] = current to dir
            if (next == to) {
                val steps = ArrayList<EncounterMapFacing>()
                var cursor = to
                while (cursor != from) {
                    val (prev, dir2) = cameFrom.getValue(cursor)
                    steps += dir2
                    cursor = prev
                }
                return steps.reversed()
            }
            queue.addLast(next)
        }
    }
    return null
}

/** Initials of a display name ("Anna Maria Beispiel" becomes "AB"): the only name a table (and the map packet) ever shows. */
fun encounterMapInitials(displayName: String): String {
    val parts = displayName.split(' ', '-', '\t').filter { it.isNotBlank() }
    val letters =
        when {
            parts.isEmpty() -> ""
            parts.size == 1 -> parts.first().take(1)
            else -> parts.first().take(1) + parts.last().take(1)
        }
    return letters.uppercase().ifBlank { "?" }
}

/**
 * The initials as they travel in a map packet: [encounterMapInitials] without lone surrogates, at most [ENCOUNTER_MAP_INITIALS_MAX]
 * characters (an upper-casing such as the German sharp s can widen a letter), never blank.
 */
fun encounterMapWireInitials(displayName: String): String =
    encounterMapInitials(displayName)
        .filter { !it.isSurrogate() }
        .take(ENCOUNTER_MAP_INITIALS_MAX)
        .ifBlank { "?" }

// ── Wire packet ──────────────────────────────────────────────────────────────

/** What the server sends: a delta (changed and removed people) or a reset ("fetch the snapshot again"). */
sealed interface EncounterMapPacket {
    data class Delta(
        val epoch: String,
        val seq: Long,
        val changed: List<EncounterMapPositionDto>,
        val removed: List<String>,
    ) : EncounterMapPacket

    data class Reset(
        val epoch: String,
        val seq: Long,
    ) : EncounterMapPacket
}

private fun EncounterMapFacing.letter(): String =
    when (this) {
        EncounterMapFacing.NORTH -> "N"
        EncounterMapFacing.EAST -> "E"
        EncounterMapFacing.SOUTH -> "S"
        EncounterMapFacing.WEST -> "W"
    }

private fun facingOfLetter(letter: String): EncounterMapFacing? =
    when (letter) {
        "N" -> EncounterMapFacing.NORTH
        "E" -> EncounterMapFacing.EAST
        "S" -> EncounterMapFacing.SOUTH
        "W" -> EncounterMapFacing.WEST
        else -> null
    }

/** `{"v":1,"t":"d","e":"<epoch>","s":<seq>,"c":[["<uuid>",x,y,"N","AB"]],"r":["<uuid>"]}` or `{"v":1,"t":"r","e":..,"s":..}`. */
fun encounterMapPayload(p: EncounterMapPacket): String =
    when (p) {
        is EncounterMapPacket.Reset -> """{"v":$ENCOUNTER_MAP_WIRE_VERSION,"t":"r","e":${quote(p.epoch)},"s":${p.seq}}"""
        is EncounterMapPacket.Delta ->
            buildString {
                append("""{"v":$ENCOUNTER_MAP_WIRE_VERSION,"t":"d","e":${quote(p.epoch)},"s":${p.seq},"c":[""")
                p.changed.forEachIndexed { i, c ->
                    if (i > 0) append(',')
                    append("[${quote(c.memberId)},${c.x},${c.y},${quote(c.facing.letter())},${quote(c.initials)}]")
                }
                append("""],"r":[""")
                p.removed.forEachIndexed { i, r ->
                    if (i > 0) append(',')
                    append(quote(r))
                }
                append("]}")
            }
    }

private fun quote(s: String): String = JsonPrimitive(s).toString()

private val ENCOUNTER_MAP_UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
private val ENCOUNTER_MAP_EPOCH = Regex("^[0-9a-f]{1,16}$")

/**
 * Parses a map packet. `null` if [fromParticipant] (a packet that carries a sending participant is a forgery), the length is outside
 * `1..ENCOUNTER_MAP_MAX_PAYLOAD_BYTES`, `v` is not 1, `t` is unknown, a coordinate is outside the grid, a facing is not N/E/S/W, a key is
 * not UUID-shaped, initials are longer than 3 characters or a list holds more than 64 entries. Unknown additional fields are ignored (forward
 * compatible). Never throws.
 */
fun encounterMapPacket(
    fromParticipant: Boolean,
    payload: ByteArray,
): EncounterMapPacket? {
    if (fromParticipant || payload.size !in 1..ENCOUNTER_MAP_MAX_PAYLOAD_BYTES) return null
    return try {
        val obj = Json.parseToJsonElement(payload.decodeToString()) as? JsonObject ?: return null
        if (intOf(obj["v"]) != ENCOUNTER_MAP_WIRE_VERSION) return null
        val epoch = stringOf(obj["e"])?.takeIf { ENCOUNTER_MAP_EPOCH.matches(it) } ?: return null
        val seq =
            (obj["s"] as? JsonPrimitive)
                ?.takeIf { !it.isString }
                ?.content
                ?.toLongOrNull()
                ?.takeIf { it >= 0 } ?: return null
        when (stringOf(obj["t"])) {
            "r" -> EncounterMapPacket.Reset(epoch = epoch, seq = seq)
            "d" -> parseDelta(obj = obj, epoch = epoch, seq = seq)
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

private fun parseDelta(
    obj: JsonObject,
    epoch: String,
    seq: Long,
): EncounterMapPacket.Delta? {
    val changedArray = obj["c"] as? JsonArray ?: return null
    val removedArray = obj["r"] as? JsonArray ?: return null
    if (changedArray.size > ENCOUNTER_MAP_PACKET_LIST_MAX || removedArray.size > ENCOUNTER_MAP_PACKET_LIST_MAX) return null
    val changed =
        changedArray.map { entry ->
            val row = entry as? JsonArray ?: return null
            if (row.size != 5) return null
            val id = stringOf(row[0])?.takeIf { ENCOUNTER_MAP_UUID.matches(it) } ?: return null
            val x = intOf(row[1])?.takeIf { it in 0 until ENCOUNTER_MAP_COLUMNS } ?: return null
            val y = intOf(row[2])?.takeIf { it in 0 until ENCOUNTER_MAP_ROWS } ?: return null
            val facing = facingOfLetter(stringOf(row[3]) ?: return null) ?: return null
            val initials = stringOf(row[4])?.takeIf { it.length in 1..ENCOUNTER_MAP_INITIALS_MAX } ?: return null
            EncounterMapPositionDto(memberId = id, x = x, y = y, facing = facing, initials = initials)
        }
    val removed = removedArray.map { stringOf(it)?.takeIf { s -> ENCOUNTER_MAP_UUID.matches(s) } ?: return null }
    return EncounterMapPacket.Delta(epoch = epoch, seq = seq, changed = changed, removed = removed)
}

private fun stringOf(element: kotlinx.serialization.json.JsonElement?): String? =
    (element as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun intOf(element: kotlinx.serialization.json.JsonElement?): Int? =
    (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
