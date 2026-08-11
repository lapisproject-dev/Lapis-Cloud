package network.lapis.cloud.server.conference

import network.lapis.cloud.shared.domain.WHITEBOARD_CANVAS_HEIGHT
import network.lapis.cloud.shared.domain.WHITEBOARD_CANVAS_WIDTH
import network.lapis.cloud.shared.domain.WhiteboardStrokeDto
import network.lapis.cloud.shared.domain.WhiteboardTool
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 7 "Whiteboard" -- server-side rasterization of a
 * whiteboard's committed strokes into a flat PNG, for
 * `network.lapis.cloud.server.rpc.ConferenceWhiteboardService.saveAsDocument`. Draws ONLY vector
 * paths/ovals via `Graphics2D` -- NEVER `Graphics2D.drawString` or any text rendering -- to
 * sidestep the AWT font subsystem entirely, which can fail or behave inconsistently on a minimal/
 * headless Linux container base image lacking fontconfig (a real, previously-unexercised risk: no
 * prior feature in this codebase has ever touched `java.awt`/`ImageIO`). If a future wave adds
 * text/labels to the rasterizer, verify fontconfig is present in the deployment image FIRST.
 *
 * Renders eraser strokes as opaque WHITE (the flat canvas background color) rather than true
 * transparency/destination-out compositing -- correct and simpler for a FLATTENED, single-layer PNG
 * export (there is no lower layer to reveal), even though the LIVE client-side canvas uses a real
 * destination-out composite for a nicer live-eraser feel.
 *
 * Renders at the SAME fixed logical canvas size ([WHITEBOARD_CANVAS_WIDTH] x
 * [WHITEBOARD_CANVAS_HEIGHT]) every client normalizes its own pointer coordinates into -- see
 * [network.lapis.cloud.shared.domain.WhiteboardStrokeWireDto] KDoc. No separate scaling logic
 * needed here as a result.
 */
object WhiteboardRasterizer {
    init {
        // Defensive -- see class KDoc "text rendering" for why this matters less here than it
        // would for a text-drawing rasterizer, but still correct hygiene for any
        // BufferedImage/Graphics2D use on a server JVM that may have no display.
        System.setProperty("java.awt.headless", "true")
    }

    fun render(strokes: List<WhiteboardStrokeDto>): ByteArray {
        val image = BufferedImage(WHITEBOARD_CANVAS_WIDTH, WHITEBOARD_CANVAS_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g: Graphics2D = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color.WHITE
            g.fillRect(0, 0, WHITEBOARD_CANVAS_WIDTH, WHITEBOARD_CANVAS_HEIGHT)
            for (stroke in strokes) {
                g.color = if (stroke.tool == WhiteboardTool.ERASER) Color.WHITE else parseHexColorOrBlack(stroke.color)
                g.stroke = BasicStroke(stroke.strokeWidth.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                if (stroke.points.size < 2) {
                    val p = stroke.points.firstOrNull() ?: continue
                    val r = stroke.strokeWidth / 2.0
                    g.fill(Ellipse2D.Double(p.x - r, p.y - r, stroke.strokeWidth, stroke.strokeWidth))
                } else {
                    val path = Path2D.Double()
                    path.moveTo(stroke.points[0].x, stroke.points[0].y)
                    stroke.points.drop(1).forEach { p -> path.lineTo(p.x, p.y) }
                    g.draw(path)
                }
            }
        } finally {
            g.dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    /** Defensive fallback only -- `ConferenceWhiteboardService.validateStroke` already enforces the color allowlist at commit time, so this should never actually hit the fallback in production. */
    private fun parseHexColorOrBlack(hex: String): Color = runCatching { Color.decode(hex) }.getOrElse { Color.BLACK }
}
