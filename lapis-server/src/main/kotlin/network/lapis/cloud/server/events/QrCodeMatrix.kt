package network.lapis.cloud.server.events

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * A QR code's module grid -- purely geometric, no rendering target baked in. [routes.EventTicketSvg]
 * renders this to an SVG string; `pdf.EventTicketPdfGenerator` draws it directly as PDF vector
 * rectangles via `LetterPdfBuilder.qrCode` -- neither path ever touches AWT/`ImageIO` (Welle
 * V1.4.3.2 plan §4.7, headless-safety requirement).
 */
internal class QrCodeMatrix(
    val size: Int,
    private val bits: BooleanArray,
) {
    init {
        require(bits.size == size * size) { "bits.size (${bits.size}) must equal size*size ($size*$size)" }
    }

    operator fun get(
        x: Int,
        y: Int,
    ): Boolean {
        require(x in 0 until size && y in 0 until size) { "($x, $y) out of bounds for size $size" }
        return bits[y * size + x]
    }

    /**
     * One [Run] per maximal contiguous horizontal stretch of set (black) modules, per row -- halves
     * the rectangle count a naive per-module renderer would emit (a QR code's modules are highly
     * horizontally clustered), in both the SVG and PDF renderers.
     */
    fun horizontalRuns(): List<Run> {
        val runs = mutableListOf<Run>()
        for (y in 0 until size) {
            var runStart = -1
            for (x in 0 until size) {
                val set = get(x, y)
                if (set && runStart < 0) {
                    runStart = x
                } else if (!set && runStart >= 0) {
                    runs += Run(x = runStart, y = y, length = x - runStart)
                    runStart = -1
                }
            }
            if (runStart >= 0) runs += Run(x = runStart, y = y, length = size - runStart)
        }
        return runs
    }

    data class Run(
        val x: Int,
        val y: Int,
        val length: Int,
    )
}

/**
 * zxing-core (Apache-2.0, pure JVM, no transitive deps besides test-scoped JUnit -- see
 * `gradle/libs.versions.toml`) wraps the actual QR encoding algorithm; this object owns only the
 * "extract a [QrCodeMatrix] out of zxing's own encoder output" step, so nothing else in this
 * codebase depends on zxing's own types directly.
 *
 * **Deliberately calls the low-level `qrcode.encoder.Encoder.encode` (returning the raw module
 * `ByteMatrix`, exactly `moduleCount x moduleCount`, no quiet-zone margin), NOT the higher-level
 * `QRCodeWriter.encode(content, format, width, height)`.** `QRCodeWriter` requires a target pixel
 * size up front and then integer-scales/pads the module grid to fit it (`renderResult`) -- since
 * the module count depends on the content length and error-correction level (i.e. is not known
 * before encoding), there is no width/height to pass that both (a) avoids `QRCodeWriter` rejecting
 * an under-sized target and (b) yields a clean, unscaled 1-boolean-per-module grid for
 * [horizontalRuns] to rasterize itself. `Encoder.encode` sidesteps the whole problem by never
 * scaling at all.
 */
internal object QrCodeEncoder {
    /** `ERROR_CORRECTION = M` (~15% recovery) -- a reasonable default for a printed/on-screen ticket, better resilience than `L` without `H`'s size cost. */
    fun encode(content: String): QrCodeMatrix {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val qrCode = Encoder.encode(content, ErrorCorrectionLevel.M, hints)
        val byteMatrix = checkNotNull(qrCode.matrix) { "zxing Encoder.encode produced no matrix for content of length ${content.length}" }
        val width = byteMatrix.width
        val height = byteMatrix.height
        require(width == height) { "zxing produced a non-square matrix (${width}x$height)" }
        val bits = BooleanArray(width * height) { i -> byteMatrix.get(i % width, i / width).toInt() != 0 }
        return QrCodeMatrix(size = width, bits = bits)
    }
}
