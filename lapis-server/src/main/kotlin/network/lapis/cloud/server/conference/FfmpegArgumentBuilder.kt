package network.lapis.cloud.server.conference

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 2 "Aufzeichnung" -- PURE, DOM/process-free
 * construction of the `ffmpeg` argument list [FfmpegGalleryComposer] runs. Deliberately split out
 * of that class so the filter-graph construction is unit-testable (`FfmpegArgumentBuilderTest`)
 * without ever invoking a real `ffmpeg` binary -- the testability lever the Wave 2 plan's own
 * §5 "Composition" calls out by name.
 *
 * ## Black-canvas-plus-overlay, NOT `xstack`
 *
 * `xstack` assumes every input exists for the whole output duration -- wrong here, since
 * recording inputs start and stop at different wall-clock offsets (people join/leave mid-meeting).
 * Instead: a `color=black` `lavfi` source is input 0, and every real video input is composited onto
 * it with its own `setpts=PTS-STARTPTS+{offset}/TB` (shifts that input's timeline to start at its
 * own recording-relative offset) followed by `overlay=...:enable='gte(t,{offset})'` (the overlay is
 * invisible -- passes the base frame through -- until that input's own offset is reached). This is
 * the ONLY way a `N`-cell layout can correctly show "nothing yet" for a participant who joined ten
 * minutes into the meeting, which `xstack` cannot express at all.
 *
 * ## Gallery grid vs. presentation layout
 *
 * No screen-share input present -> a roughly-square grid (`ceil(sqrt(n))` columns), each track an
 * equal-size cell. A screen-share input present -> presentation layout: the screen share renders
 * full-width in the upper `1 - CAMERA_STRIP_HEIGHT_FRACTION` of the frame, every OTHER video input
 * renders as an equal-width strip along the bottom.
 *
 * ## Locale
 *
 * Every floating-point value embedded in the filter graph ([formatSeconds]) is formatted with
 * [Locale.ROOT] explicitly -- `String.format`'s default locale would render `1.5` as `"1,500"` on a
 * JVM whose default locale uses a comma decimal separator (a real risk in this codebase's own
 * German-language deployment context), which `ffmpeg`'s filter-graph parser cannot parse at all.
 */
object FfmpegArgumentBuilder {
    /** Fraction of [RecordingComposeSpec.outputHeight] the camera strip occupies in presentation layout. */
    private const val CAMERA_STRIP_HEIGHT_FRACTION = 0.28

    /**
     * Builds the full `ffmpeg` argument list (everything after the `ffmpeg` binary name itself) to
     * compose [spec] into [outputPath]. Pure -- reads nothing from disk, spawns nothing; every
     * [java.io.File] in [spec] is used purely for its [java.io.File.getAbsolutePath] string.
     */
    fun build(
        spec: RecordingComposeSpec,
        outputPath: String,
    ): List<String> {
        require(
            spec.videoInputs.isNotEmpty(),
        ) { "RecordingComposeSpec.videoInputs must not be empty -- composition needs at least one video" }

        val durationStr = formatSeconds(spec.outputDurationSeconds)
        val args = mutableListOf("-y")
        args +=
            listOf(
                "-f",
                "lavfi",
                "-t",
                durationStr,
                "-i",
                "color=black:size=${spec.outputWidth}x${spec.outputHeight}:rate=${spec.frameRate}",
            )
        spec.videoInputs.forEach { args += listOf("-i", it.file.absolutePath) }
        if (spec.audioInputs.isEmpty()) {
            args += listOf("-f", "lavfi", "-t", durationStr, "-i", "anullsrc=r=48000:cl=stereo")
        } else {
            spec.audioInputs.forEach { args += listOf("-i", it.file.absolutePath) }
        }

        val cells = layoutCells(spec)
        args += listOf("-filter_complex", buildFilterGraph(spec, cells))
        args += listOf("-map", "[vout]", "-map", "[aout]")
        args +=
            listOf(
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-crf",
                "23",
                "-pix_fmt",
                "yuv420p",
                "-c:a",
                "aac",
                "-b:a",
                "128k",
                "-movflags",
                "+faststart",
                // Safety net on top of the explicit -t bounds above -- if a real input somehow runs
                // longer than its own recorded duration, still never exceed the shortest mapped
                // output stream.
                "-shortest",
            )
        args += outputPath
        return args
    }

    private data class Cell(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    private fun layoutCells(spec: RecordingComposeSpec): List<Cell> {
        val screenShareIndex = spec.videoInputs.indexOfFirst { it.isScreenShare }
        return if (screenShareIndex >= 0) presentationCells(spec, screenShareIndex) else galleryCells(spec)
    }

    /** Roughly-square grid -- see class KDoc "Gallery grid vs. presentation layout". */
    private fun galleryCells(spec: RecordingComposeSpec): List<Cell> {
        val count = spec.videoInputs.size
        val cols = ceil(sqrt(count.toDouble())).toInt().coerceAtLeast(1)
        val rows = ceil(count.toDouble() / cols).toInt().coerceAtLeast(1)
        val cellWidth = spec.outputWidth / cols
        val cellHeight = spec.outputHeight / rows
        return (0 until count).map { i ->
            Cell(x = (i % cols) * cellWidth, y = (i / cols) * cellHeight, width = cellWidth, height = cellHeight)
        }
    }

    /** Screen share full-width above, remaining video inputs as an equal-width strip below -- see class KDoc. */
    private fun presentationCells(
        spec: RecordingComposeSpec,
        screenShareIndex: Int,
    ): List<Cell> {
        val cameraIndices = spec.videoInputs.indices.filter { it != screenShareIndex }
        val stripHeight = (spec.outputHeight * CAMERA_STRIP_HEIGHT_FRACTION).toInt()
        val shareHeight = if (cameraIndices.isEmpty()) spec.outputHeight else spec.outputHeight - stripHeight
        val cells = MutableList(spec.videoInputs.size) { Cell(0, 0, spec.outputWidth, spec.outputHeight) }
        cells[screenShareIndex] = Cell(x = 0, y = 0, width = spec.outputWidth, height = shareHeight)
        if (cameraIndices.isNotEmpty()) {
            val cellWidth = spec.outputWidth / cameraIndices.size
            cameraIndices.forEachIndexed { position, index ->
                cells[index] = Cell(x = position * cellWidth, y = shareHeight, width = cellWidth, height = stripHeight)
            }
        }
        return cells
    }

    /** See class KDoc "Black-canvas-plus-overlay, NOT xstack" for the video chain, and its own body for the audio chain. */
    private fun buildFilterGraph(
        spec: RecordingComposeSpec,
        cells: List<Cell>,
    ): String {
        val parts = mutableListOf<String>()
        var previousLabel = "0:v" // input 0 is always the black-canvas base.

        spec.videoInputs.forEachIndexed { i, input ->
            val inputIndex = i + 1 // input 0 is the black canvas, real video inputs start at 1.
            val cell = cells[i]
            val offset = formatSeconds(input.offsetSeconds)
            val scaledLabel = "v$i"
            parts +=
                "[$inputIndex:v]setpts=PTS-STARTPTS+$offset/TB," +
                "scale=${cell.width}:${cell.height}:force_original_aspect_ratio=decrease," +
                "pad=${cell.width}:${cell.height}:(ow-iw)/2:(oh-ih)/2:color=black[$scaledLabel]"
            val overlayLabel = if (i == spec.videoInputs.lastIndex) "vout" else "ov$i"
            parts += "[$previousLabel][$scaledLabel]overlay=x=${cell.x}:y=${cell.y}:enable='gte(t,$offset)'[$overlayLabel]"
            previousLabel = overlayLabel
        }

        val videoInputCount = spec.videoInputs.size
        if (spec.audioInputs.isEmpty()) {
            val silentInputIndex = 1 + videoInputCount
            parts += "[$silentInputIndex:a]anull[aout]"
        } else {
            val audioLabels = mutableListOf<String>()
            spec.audioInputs.forEachIndexed { i, input ->
                val inputIndex = 1 + videoInputCount + i
                val label = "a$i"
                val delayMs = formatDelayMs(input.offsetSeconds)
                parts += "[$inputIndex:a]adelay=$delayMs|$delayMs[$label]"
                audioLabels += "[$label]"
            }
            parts += "${audioLabels.joinToString("")}amix=inputs=${audioLabels.size}:dropout_transition=0:normalize=0[aout]"
        }

        return parts.joinToString(";")
    }

    /** Always [Locale.ROOT] -- see class KDoc "Locale". Never negative (a track's own offset is always >= 0 relative to `t0`). */
    private fun formatSeconds(seconds: Double): String = String.format(Locale.ROOT, "%.3f", seconds.coerceAtLeast(0.0))

    private fun formatDelayMs(seconds: Double): String = (seconds.coerceAtLeast(0.0) * 1000).toLong().toString()
}
