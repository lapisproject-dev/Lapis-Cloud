package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.html.Div
import io.kvision.html.div
import network.lapis.cloud.shared.domain.EncounterRoomLayout

/*
 * V1.9.152 Begegnungsraum Stufe 2c -- the fixed room templates as DATA: which drawings stand where in which template. Constants and one pure renderer,
 * nothing else. The renderer is the ONE source of the picture: the room (`EncounterSceneLayout`) and the preview tiles of the room form
 * (`EncounterSpaceScreen`) both call [renderLayoutScene], so there are no second preview images that could drift from the room.
 *
 * **Privacy by construction**: a template describes the ROOM and is identical for everybody present. This file knows no member, no participant, no seat
 * occupancy and no identity (`ClientEncounterPrivacyTripwireTest` fails the build if a later change adds one); it is decoration for the eye only
 * (`aria-hidden` -- the one spoken sentence about the template is [layoutRoomSentence]).
 */

/** The ten drawings of `webAssets/encounter-objects-v1` (file name without `.svg`). */
internal enum class EncounterObjectKind(
    val file: String,
) {
    ALTAR("altar"),
    PODIUM("podium"),
    PLANT("plant"),
    COUNTER("counter"),
    WHITEBOARD("whiteboard"),
    TABLE_ROUND("table-round"),
    TABLE_LONG("table-long"),
    BLOCK_PEW("block-pew"),
    BLOCK_CHAIR("block-chair"),
    BLOCK_STOOL("block-stool"),
}

/**
 * FRONT = the stage strip ABOVE the pulpit (where the speakers stand), REAR = the back-wall strip BELOW the seats. Since V1.9.153 both strips are
 * ordinary blocks in the flow of the stage, so a drawing can never slide under the opaque pulpit card or under a hint line.
 */
internal enum class EncounterZone { FRONT, REAR }

/** One drawing in a template: [xPct] is the horizontal centre in percent of the room's width, [wPx] x [hPx] its size at scale 1. */
internal data class PlacedObject(
    val kind: EncounterObjectKind,
    val zone: EncounterZone,
    val xPct: Int,
    val wPx: Int,
    val hPx: Int,
)

/**
 * A template: the placed [objects] (at most [ENCOUNTER_LAYOUT_MAX_OBJECTS]), the tile drawn behind each block of three seats ([block], 160 x 48), the drawing
 * behind a table card ([table]) and the most table columns that fit side by side ([tableColumns], `0` = as many as fit).
 */
internal data class RoomLayoutSpec(
    val objects: List<PlacedObject>,
    val block: EncounterObjectKind,
    val table: EncounterObjectKind,
    val tableColumns: Int,
) {
    /** A bench is one long drawing: its tile is stretched to the width of the block (chairs and stools keep their size). */
    val stretch: Boolean get() = block == EncounterObjectKind.BLOCK_PEW
}

/** The versioned, immutable path the drawings are served from (staged by `stageVideoEffectAssets`, checksums in `lapis-client/build.gradle.kts`). */
internal const val ENCOUNTER_OBJECT_DIR = "/assets/encounter-objects-v1"

/** More drawings than this clutter the room; a unit test pins it. */
internal const val ENCOUNTER_LAYOUT_MAX_OBJECTS = 12

internal fun encounterObjectPath(kind: EncounterObjectKind): String = "$ENCOUNTER_OBJECT_DIR/${kind.file}.svg"

/** The CSS modifier of a template (`NAVE` -> `nave`, `SMALL_GROUP` -> `small-group`). */
internal fun layoutCssKey(layout: EncounterRoomLayout): String = layout.name.lowercase().replace('_', '-')

internal object EncounterRoomLayouts {
    private fun front(
        kind: EncounterObjectKind,
        xPct: Int,
        wPx: Int,
        hPx: Int,
    ) = PlacedObject(kind = kind, zone = EncounterZone.FRONT, xPct = xPct, wPx = wPx, hPx = hPx)

    private fun rear(
        kind: EncounterObjectKind,
        xPct: Int,
        wPx: Int,
        hPx: Int,
    ) = PlacedObject(kind = kind, zone = EncounterZone.REAR, xPct = xPct, wPx = wPx, hPx = hPx)

    private val nave =
        RoomLayoutSpec(
            objects =
                listOf(
                    front(kind = EncounterObjectKind.ALTAR, xPct = 50, wPx = 220, hPx = 110),
                    front(kind = EncounterObjectKind.PLANT, xPct = 10, wPx = 44, hPx = 44),
                    front(kind = EncounterObjectKind.PLANT, xPct = 90, wPx = 44, hPx = 44),
                    rear(kind = EncounterObjectKind.PLANT, xPct = 5, wPx = 44, hPx = 44),
                    rear(kind = EncounterObjectKind.PLANT, xPct = 95, wPx = 44, hPx = 44),
                ),
            block = EncounterObjectKind.BLOCK_PEW,
            table = EncounterObjectKind.TABLE_ROUND,
            tableColumns = 0,
        )

    private val foyer =
        RoomLayoutSpec(
            objects =
                listOf(
                    front(kind = EncounterObjectKind.COUNTER, xPct = 50, wPx = 280, hPx = 70),
                    front(kind = EncounterObjectKind.PLANT, xPct = 12, wPx = 44, hPx = 44),
                    front(kind = EncounterObjectKind.PLANT, xPct = 88, wPx = 44, hPx = 44),
                    rear(kind = EncounterObjectKind.PLANT, xPct = 5, wPx = 44, hPx = 44),
                    rear(kind = EncounterObjectKind.PLANT, xPct = 95, wPx = 44, hPx = 44),
                ),
            block = EncounterObjectKind.BLOCK_STOOL,
            table = EncounterObjectKind.TABLE_ROUND,
            tableColumns = 3,
        )

    private val hall =
        RoomLayoutSpec(
            objects =
                listOf(
                    front(kind = EncounterObjectKind.PODIUM, xPct = 50, wPx = 200, hPx = 100),
                    front(kind = EncounterObjectKind.PLANT, xPct = 12, wPx = 44, hPx = 44),
                    front(kind = EncounterObjectKind.PLANT, xPct = 88, wPx = 44, hPx = 44),
                    rear(kind = EncounterObjectKind.PLANT, xPct = 95, wPx = 44, hPx = 44),
                ),
            block = EncounterObjectKind.BLOCK_CHAIR,
            table = EncounterObjectKind.TABLE_ROUND,
            tableColumns = 0,
        )

    private val smallGroup =
        RoomLayoutSpec(
            objects =
                listOf(
                    front(kind = EncounterObjectKind.WHITEBOARD, xPct = 50, wPx = 240, hPx = 80),
                    front(kind = EncounterObjectKind.PLANT, xPct = 90, wPx = 44, hPx = 44),
                    rear(kind = EncounterObjectKind.PLANT, xPct = 6, wPx = 44, hPx = 44),
                ),
            block = EncounterObjectKind.BLOCK_CHAIR,
            table = EncounterObjectKind.TABLE_LONG,
            tableColumns = 2,
        )

    fun spec(layout: EncounterRoomLayout): RoomLayoutSpec =
        when (layout) {
            EncounterRoomLayout.NAVE -> nave
            EncounterRoomLayout.FOYER -> foyer
            EncounterRoomLayout.HALL -> hall
            EncounterRoomLayout.SMALL_GROUP -> smallGroup
        }
}

private fun Container.placedObject(
    placed: PlacedObject,
    scale: Double,
    sized: Boolean = true,
): Div {
    val zone = placed.zone.name.lowercase()
    val classes = "lapis-encounter-scene-object lapis-encounter-scene-object--${placed.kind.file} lapis-encounter-scene-object--$zone"
    val drawing = div(className = classes)
    drawing.setStyle("--lapis-enc-object", "url(\"${encounterObjectPath(placed.kind)}\")")
    drawing.setStyle("left", "${placed.xPct}%")
    if (sized) {
        drawing.setStyle("width", "${(placed.wPx * scale).toInt()}px")
        drawing.setStyle("height", "${(placed.hPx * scale).toInt()}px")
    }
    return drawing
}

/**
 * V1.9.153: one strip of the room's scene, IN THE FLOW of the stage (not an absolute layer under it): the FRONT strip stands above the pulpit card,
 * the REAR strip below the seats and their hint lines. Its height comes from the CSS (`.lapis-encounter-scene-zone--front|rear`, smaller on a phone);
 * each drawing fills that height and keeps its aspect ratio. `aria-hidden`, not animated.
 */
internal fun renderSceneZone(
    parent: Container,
    layout: EncounterRoomLayout,
    zone: EncounterZone,
): Div {
    val key = zone.name.lowercase()
    val classes = "lapis-encounter-scene-zone lapis-encounter-scene-zone--$key lapis-encounter-scene-zone--${layoutCssKey(layout)}"
    val strip = parent.div(className = classes)
    strip.setAttribute("aria-hidden", "true")
    EncounterRoomLayouts.spec(layout).objects.filter { it.zone == zone }.forEach { placed ->
        // The size comes from the strip's height and the aspect ratio, not from fixed pixels.
        val drawing = strip.placedObject(placed, scale = 1.0, sized = false)
        drawing.setStyle("aspect-ratio", "${placed.wPx} / ${placed.hPx}")
    }
    return strip
}

/**
 * Draws [layout] into [parent] and returns the layer: one `aria-hidden` div per drawing, each a CSS mask (`theme.css`,
 * `.lapis-encounter-scene-object`; the colour is the design token `--lapis-encounter-scene-ink`). The mask URL is set inline because the bundler
 * would try to resolve a `url()` in `theme.css` at build time and the files are runtime assets.
 *
 * Since V1.9.153 only the PREVIEW tiles of the room form use this absolute layer; the room itself draws its two strips with [renderSceneZone].
 * [scale] shrinks the sizes of the drawings. [withRows] adds two sample rows of the template's seat blocks below the front drawings: the same
 * `block` structure as the room (three dummy seats per block), so the preview shows what the room shows.
 */
internal fun renderLayoutScene(
    parent: Container,
    layout: EncounterRoomLayout,
    scale: Double = 1.0,
    withRows: Boolean = false,
): Div {
    val spec = EncounterRoomLayouts.spec(layout)
    val layer = parent.div(className = "lapis-encounter-scene-layer lapis-encounter-scene-layer--${layoutCssKey(layout)}")
    layer.setAttribute("aria-hidden", "true")
    spec.objects.forEach { placed -> layer.placedObject(placed, scale) }
    if (withRows) {
        val seats = layer.div(className = "lapis-encounter-preview-seats")
        seats.setStyle("--lapis-enc-preview-scale", scale.toString())
        val benches = seats.div(className = "lapis-encounter-benches")
        benches.setStyle("--lapis-enc-scene-block", "url(\"${encounterObjectPath(spec.block)}\")")
        if (spec.stretch) benches.addCssClass("lapis-encounter-benches--stretch")
        repeat(2) {
            val row = benches.div(className = "lapis-encounter-row")
            repeat(2) {
                val block = row.div(className = "lapis-encounter-block")
                repeat(3) { block.div(className = "lapis-encounter-seat-dummy") }
            }
        }
    }
    return layer
}
