package network.lapis.cloud.server.conference

import network.lapis.cloud.shared.domain.ConferenceBackgroundRules
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam
import javax.imageio.ImageReader
import javax.imageio.plugins.jpeg.JPEGImageWriteParam
import javax.imageio.stream.MemoryCacheImageInputStream

/**
 * Welle V1.9.4 "private Hintergrundbild-Uploads für Videokonferenzen" -- decodes an untrusted
 * member-supplied JPEG/PNG, re-encodes it as a flat, EXIF/ICC/XMP-stripped JPEG (main image +
 * thumbnail), and returns nothing but pixels + a checksum. See `ConferenceBackgroundRoutes` for
 * the HTTP layer that calls this, and `docs/architecture/video-background-effects.adoc` § "Custom
 * backgrounds" for the full rationale.
 *
 * **This class is the whole security boundary against a malicious image file.** Every step below
 * is deliberate hardening, not incidental:
 * 1. **Headless AWT** -- `java.awt.headless=true` set in the companion `init` block, BEFORE any
 *    other AWT class is touched anywhere in this class (see [WhiteboardRasterizer] KDoc "text
 *    rendering" for why this matters on a minimal/headless Linux container).
 * 2. **Reader chosen by SNIFFED MIME type, never format auto-detection** -- `"jpeg"`/`"png"` maps
 *    to `ImageIO.getImageReadersByFormatName(fmt).next()`. `ImageIO.read(InputStream)`'s own
 *    auto-detection is never used: it would let a polyglot file masquerade as whichever format its
 *    magic bytes happen to satisfy first.
 * 3. **`ignoreMetadata = true`** on `reader.setInput(...)` -- the reader never parses/retains
 *    zTXt/iCCP/iTXt (PNG) or APPn (JPEG) chunks in the first place; combined with step 8 below
 *    (re-encoding with no metadata objects passed to the writer), no EXIF GPS tag, XMP packet or
 *    embedded ICC profile ever reaches the stored file.
 * 4. **Dimensions read from the file HEADER ONLY, before any decoding** -- `reader.getWidth(0)`/
 *    `getHeight(0)` is answered from the image header alone for both JPEG and PNG (verified
 *    behaviour of the JDK's built-in readers). A dimension outside
 *    [ConferenceBackgroundRules.MIN_SIDE_PX]..[ConferenceBackgroundRules.MAX_SIDE_PX] is rejected
 *    as [BackgroundImageOutcome.DimensionsOutOfRange] BEFORE [decode] is ever invoked -- this is
 *    what stops a "20000×20000 IHDR, 200-byte IDAT" decompression bomb from ever allocating a
 *    multi-gigabyte raster: [decodeCallCountForTest] stays at 0 for such a file, see
 *    `ConferenceBackgroundImageProcessorTest`.
 * 4a. **Progressive-JPEG "scan bomb" guard, also HEADER-ONLY, also before [decode]** -- Security-
 *    Audit fix (2026-09-27, MAJOR "CPU DoS via a progressive-JPEG scan bomb"): a dimension-gate-
 *    passing JPEG can still be a decode-time bomb. The JDK's built-in JPEG reader accepts an
 *    unbounded number of SOS (Start Of Scan) marker segments -- a legitimate progressive JPEG has
 *    roughly a handful (~10); nothing stops a crafted file from repeating a tiny redundant scan
 *    hundreds of thousands of times. The reader only WARNS about this, never rejects it, and walks
 *    every block of the image again for each one -- empirically, a 115 KB crafted file with 1000
 *    duplicated scans took over a minute to decode where the un-tampered original took well under a
 *    second, and the native decode is blocking/uncancellable (a client disconnect does not stop
 *    it). [jpegScanCountExceeds] walks the marker structure exactly the way a decoder must (skip
 *    each segment by its own length field, then skip entropy-coded scan data via byte-stuffing/
 *    restart-marker rules until the next real marker) purely to COUNT SOS segments, and rejects as
 *    [BackgroundImageOutcome.Undecodable] the moment that count exceeds [MAX_JPEG_SCANS] --
 *    generously above what any legitimate progressive file needs, and [decode] is never invoked for
 *    such a file (same `decodeCallCountForTest == 0` proof as point 4). Follow-up Security-Audit fix
 *    (2026-09-27, MAJOR "scan-bomb fix bypassed by a single extraneous byte"): the walk used to
 *    treat any byte other than `0xFF` at a marker boundary as "malformed, let decode() judge it" and
 *    return `false` -- but the JDK's reader (like libjpeg) tolerates a stray byte there as merely
 *    extraneous data and decodes the file anyway, so one inserted `0x00` byte before the crafted
 *    file's first SOS marker made the whole gate return `false` regardless of true scan count. The
 *    walk now skips a single unexpected byte and keeps looking for the next real marker instead of
 *    bailing out -- see [jpegScanCountExceeds] KDoc. Second follow-up Security-Audit fix
 *    (2026-09-27, MAJOR "scan-bomb gate still bypassable via a bogus 2-byte length"): the round-2
 *    fix above only covered a stray byte sitting where a marker byte was expected. It did NOT cover
 *    a marker that IS validly `0xFF`-prefixed but whose declared 2-byte length is bogus (`< 2`,
 *    i.e. shorter than the length field itself) or whose marker code is `0x00` (`0xFF 0x00`,
 *    byte-stuffing syntax that is only legal INSIDE entropy-coded scan data, never at a marker
 *    boundary). Both the JDK reader and libjpeg (`save_marker`/`skip_variable`: "deal with bogus
 *    length word" by treating it as the 2-byte minimum; `next_marker`: `0xFF 0x00` is discarded as
 *    merely extraneous data) tolerate these and keep decoding -- but the walk used to treat a bogus
 *    length as "malformed, let decode() judge it" and return `false`, silently ending the walk (so
 *    every SOS after that point went uncounted) the moment such a marker appeared before the first
 *    genuine SOS. Empirically confirmed: `scan-bomb.jpg` (50 scans) with one of `FF FE 00 00` (COM,
 *    length 0), `FF E1 00 01` (APP1, length 1) or `FF 00 00 00` spliced in right before its first
 *    SOS, followed by 500 more copies of its smallest scan (550 scans total, 156,735 bytes, still
 *    4096x4096), made the real, compiled `process()` return `Ok` after ~25 s of blocking native
 *    decode instead of being rejected -- see `scan-bomb-bogus-length-{com,app1,ff00}.jpg` fixtures
 *    and the corresponding `ConferenceBackgroundImageProcessorTest` regressions.
 *    [jpegScanCountExceeds] now fails CLOSED (returns `true`, i.e. rejects as a scan bomb) for every
 *    marker-structure anomaly encountered after a valid SOI -- a bogus length (`< 2`), a length
 *    field or SOS header running past the end of the file, or a `0x00` marker code -- rather than
 *    falling back to "not exceeding, let decode() judge it". This is strictly safe for legitimate
 *    uploads: every fixture that must decode successfully comes from an ordinary encoder and never
 *    exercises any of these anomaly branches in the first place (the client re-encodes anyway, so a
 *    false-positive `Undecodable` on a genuinely malformed-but-harmless file costs nothing). A
 *    missing/invalid SOI at the very start of the file, and an isolated stray (non-`0xFF`) byte
 *    between markers, intentionally remain `false` -- see [jpegScanCountExceeds] KDoc.
 * 5. **Subsampling** -- `ImageReadParam.setSourceSubsampling` further bounds decoded-raster memory
 *    for any image that DOES pass the dimension gate but is still much larger than the eventual
 *    output, so the peak raster size stays roughly proportional to the OUTPUT size, not the
 *    input's. Computed as `longEdge / MAX_OUTPUT_LONG_EDGE_PX` (Security-Audit fix 2026-09-27,
 *    INFO finding: the previous `longEdge / (2 * MAX_OUTPUT_LONG_EDGE_PX)` formula was always 1 for
 *    every size this processor accepts, since `MAX_SIDE_PX` (4096) never reaches `2 *
 *    MAX_OUTPUT_LONG_EDGE_PX` (3840*2) -- source subsampling never actually took effect, contrary
 *    to this KDoc's own claim).
 * 6. **Always re-drawn into a fresh `TYPE_INT_RGB` canvas** -- an ARGB/paletted/CMYK source is
 *    NEVER written back byte-for-byte; every pixel is repainted onto an opaque `#202020`-filled
 *    canvas via `Graphics2D` (this also flattens any alpha channel, and is what makes a
 *    polyglot-appended trailer, e.g. HTML/ZIP bytes appended after a valid JPEG's EOI marker,
 *    structurally impossible to survive -- the OUTPUT bytes come entirely from a fresh raster,
 *    never a byte range of the input).
 * 7. **Never upscaled** -- [backgroundTargetSize] only ever shrinks, never enlarges.
 * 8. **Re-encoded with NO stream/image metadata objects** -- `writer.write(null, IIOImage(img,
 *    null, null), param)`: the JDK's own JPEG writer emits only SOI/APP0-JFIF/DQT/SOF/DHT/SOS/EOI
 *    for such a call -- there is no APP1 (EXIF/XMP) and no APP2 (ICC) segment for it to have
 *    carried over even if step 3 above had failed to strip them.
 * 9. **`reader`/`writer`/`Graphics2D` always disposed, streams always closed** -- `finally`/`use`
 *    throughout, even on the exception paths below.
 *
 * [decode] is a test seam ONLY -- the decompression-bomb test replaces it with a call-counting
 * stub to prove [decode] is never reached once step 4 has already rejected the file.
 */
internal class ConferenceBackgroundImageProcessor(
    private val decode: (ImageReader, ImageReadParam) -> BufferedImage = { reader, param -> reader.read(0, param) },
) {
    companion object {
        init {
            // See class KDoc "Headless AWT" -- must run before any other AWT class is touched.
            System.setProperty("java.awt.headless", "true")
            // DoS/resource hardening: without this, ImageIO.createImageOutputStream (used by
            // encodeJpeg below) may spill to an on-disk temp file for buffering under load --
            // unnecessary here (the whole main/thumb output is small and short-lived) and a
            // needless temp-file/disk-exhaustion surface under concurrent uploads.
            ImageIO.setUseCache(false)
        }

        private const val JPEG_QUALITY_MAIN = 0.85f
        private const val JPEG_QUALITY_THUMB = 0.80f
        private const val CANVAS_FILL_HEX = 0x202020

        /**
         * See class KDoc point 4a. A genuine progressive JPEG produced by ordinary tooling sits
         * around 10 scans; this is deliberately generous headroom above that, while still bounding
         * a crafted file to at most a few dozen full-image decode passes instead of thousands.
         */
        private const val MAX_JPEG_SCANS = 32

        /**
         * Runtime guard independent of [jpegScanCountExceeds] -- Security-Audit follow-up
         * (2026-09-27, recommended alongside the scan-bomb fixes above, "still not implemented"
         * after two prior rounds): [ImageReader.read] is blocking, uncancellable native code (a
         * client disconnect does not stop it -- see class KDoc point 4a), and the marker walk above
         * is a best-effort, format-aware heuristic, not a formal proof that every conceivable
         * pathological-but-genuinely-decodable JPEG/PNG completes quickly. [ImageReader.abort] is
         * the JDK-documented way to interrupt a read mid-decode ("the reader will attempt to exit
         * gracefully as soon as it can while still returning either an appropriate exception or the
         * bytes that were being read"). [process] schedules exactly one `abort()` call on
         * [decodeWatchdog] right before invoking [decode], cancels it the instant [decode] returns,
         * and relies on the existing `catch (e: Exception) -> Undecodable` around that call --
         * `abort()` makes an in-flight [ImageReader.read] throw, so no new outcome type is needed.
         * A single shared daemon thread is enough: scheduling/cancelling a timer is near-instant and
         * never blocks on the decode itself, which always runs on the caller's own thread.
         */
        private const val DECODE_WATCHDOG_TIMEOUT_SECONDS = 10L
        private val decodeWatchdog: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "conference-bg-decode-watchdog").apply { isDaemon = true }
            }
    }

    fun process(
        bytes: ByteArray,
        sniffedMime: String,
    ): BackgroundImageOutcome {
        val formatName =
            when (sniffedMime) {
                "image/jpeg" -> "jpeg"
                "image/png" -> "png"
                else -> return BackgroundImageOutcome.Undecodable
            }
        val reader = ImageIO.getImageReadersByFormatName(formatName).asSequence().firstOrNull() ?: return BackgroundImageOutcome.Undecodable
        try {
            val stream = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
            reader.setInput(stream, true, true) // seekForwardOnly, ignoreMetadata -- see class KDoc.

            val sourceWidth: Int
            val sourceHeight: Int
            try {
                sourceWidth = reader.getWidth(0)
                sourceHeight = reader.getHeight(0)
            } catch (e: Exception) {
                return BackgroundImageOutcome.Undecodable
            }

            if (sourceWidth < ConferenceBackgroundRules.MIN_SIDE_PX ||
                sourceHeight < ConferenceBackgroundRules.MIN_SIDE_PX ||
                sourceWidth > ConferenceBackgroundRules.MAX_SIDE_PX ||
                sourceHeight > ConferenceBackgroundRules.MAX_SIDE_PX
            ) {
                // Rejected BEFORE decode() is ever called -- see class KDoc point 4.
                return BackgroundImageOutcome.DimensionsOutOfRange
            }

            if (formatName == "jpeg" && jpegScanCountExceeds(bytes = bytes, limit = MAX_JPEG_SCANS)) {
                // Rejected BEFORE decode() is ever called -- see class KDoc point 4a.
                return BackgroundImageOutcome.Undecodable
            }

            val (targetWidth, targetHeight) =
                backgroundTargetSize(
                    width = sourceWidth,
                    height = sourceHeight,
                    maxLongEdge = ConferenceBackgroundRules.MAX_OUTPUT_LONG_EDGE_PX,
                )

            val longEdge = maxOf(sourceWidth, sourceHeight)
            val subsampling = maxOf(1, longEdge / ConferenceBackgroundRules.MAX_OUTPUT_LONG_EDGE_PX)
            val readParam =
                reader.defaultReadParam.apply {
                    if (subsampling > 1) setSourceSubsampling(subsampling, subsampling, 0, 0)
                }

            val source =
                try {
                    // See companion KDoc "Runtime guard independent of jpegScanCountExceeds" --
                    // aborts the native decode if it runs unexpectedly long, independent of the
                    // marker-walk gate above.
                    val abortTask =
                        decodeWatchdog.schedule({ reader.abort() }, DECODE_WATCHDOG_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    try {
                        decode(reader, readParam)
                    } finally {
                        abortTask.cancel(false)
                    }
                } catch (e: Exception) {
                    return BackgroundImageOutcome.Undecodable
                }

            val main = redraw(source = source, targetWidth = targetWidth, targetHeight = targetHeight)
            val thumb =
                redrawCover(
                    source = source,
                    targetWidth = ConferenceBackgroundRules.THUMB_WIDTH_PX,
                    targetHeight = ConferenceBackgroundRules.THUMB_HEIGHT_PX,
                )

            val mainBytes = encodeJpeg(image = main, quality = JPEG_QUALITY_MAIN)
            val thumbBytes = encodeJpeg(image = thumb, quality = JPEG_QUALITY_THUMB)
            val digest = MessageDigest.getInstance("SHA-256")
            val sha256Hex = digest.digest(mainBytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

            return BackgroundImageOutcome.Ok(
                main = mainBytes,
                thumb = thumbBytes,
                width = targetWidth,
                height = targetHeight,
                sha256Hex = sha256Hex,
            )
        } catch (e: Exception) {
            return BackgroundImageOutcome.Undecodable
        } finally {
            reader.dispose()
        }
    }

    private fun redraw(
        source: BufferedImage,
        targetWidth: Int,
        targetHeight: Int,
    ): BufferedImage {
        val out = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        val g: Graphics2D = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.color = Color(CANVAS_FILL_HEX)
            g.fillRect(0, 0, targetWidth, targetHeight)
            g.drawImage(source, 0, 0, targetWidth, targetHeight, null)
        } finally {
            g.dispose()
        }
        return out
    }

    /** "cover" crop -- fills [targetWidth]x[targetHeight] entirely, cropping the longer axis, never letterboxing. */
    private fun redrawCover(
        source: BufferedImage,
        targetWidth: Int,
        targetHeight: Int,
    ): BufferedImage {
        val sourceRatio = source.width.toDouble() / source.height.toDouble()
        val targetRatio = targetWidth.toDouble() / targetHeight.toDouble()
        val drawWidth: Int
        val drawHeight: Int
        if (sourceRatio > targetRatio) {
            drawHeight = targetHeight
            drawWidth = (targetHeight * sourceRatio).toInt().coerceAtLeast(1)
        } else {
            drawWidth = targetWidth
            drawHeight = (targetWidth / sourceRatio).toInt().coerceAtLeast(1)
        }
        val offsetX = (targetWidth - drawWidth) / 2
        val offsetY = (targetHeight - drawHeight) / 2

        val out = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        val g: Graphics2D = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.color = Color(CANVAS_FILL_HEX)
            g.fillRect(0, 0, targetWidth, targetHeight)
            g.drawImage(source, offsetX, offsetY, drawWidth, drawHeight, null)
        } finally {
            g.dispose()
        }
        return out
    }

    /** See class KDoc point 8 -- no stream/image metadata is ever passed to the writer. */
    private fun encodeJpeg(
        image: BufferedImage,
        quality: Float,
    ): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().first()
        val output = ByteArrayOutputStream()
        try {
            ImageIO.createImageOutputStream(output).use { ios ->
                writer.output = ios
                val param =
                    (writer.defaultWriteParam as JPEGImageWriteParam).apply {
                        compressionMode = JPEGImageWriteParam.MODE_EXPLICIT
                        compressionQuality = quality
                    }
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return output.toByteArray()
    }
}

internal sealed interface BackgroundImageOutcome {
    data class Ok(
        val main: ByteArray,
        val thumb: ByteArray,
        val width: Int,
        val height: Int,
        val sha256Hex: String,
    ) : BackgroundImageOutcome

    /** -> 415, unrecognized/corrupt/unsupported-color-model file. */
    data object Undecodable : BackgroundImageOutcome

    /** -> 422, header dimensions outside [ConferenceBackgroundRules.MIN_SIDE_PX]..[ConferenceBackgroundRules.MAX_SIDE_PX]. */
    data object DimensionsOutOfRange : BackgroundImageOutcome
}

/**
 * Pure, header/marker-only, never invokes the JDK's JPEG decoder. See class KDoc point 4a for the
 * full rationale ("CPU DoS via a progressive-JPEG scan bomb").
 *
 * Walks the JPEG marker structure the same way any conforming decoder must -- generic segments are
 * skipped by their own 2-byte length field; SOS (Start Of Scan, `0xFFDA`) segments are counted, then
 * their entropy-coded scan data is skipped by honoring byte-stuffing (`0xFF00` = a literal `0xFF`
 * data byte) and restart markers (`0xFFD0`-`0xFFD7`, scan data continues) until the next REAL
 * marker is reached -- so it never mistakes entropy-coded bytes for a marker, and never needs to
 * actually decode a single pixel.
 *
 * Returns `true` (without finishing the walk) the moment the SOS count exceeds [limit]. Returns
 * `false` -- "not exceeding, let the real decoder be the judge" -- ONLY for a missing/invalid SOI at
 * the very start of the file, an isolated stray (non-`0xFF`) byte between markers (see the round-2
 * fix below), or a well-formed marker stream that genuinely never accumulates enough SOS segments; a
 * file malformed enough to fail SOI validation is, for that reason, overwhelmingly likely to also
 * fail the real decode outright rather than succeed as a multi-hour scan bomb.
 *
 * For every OTHER parse anomaly encountered after a valid SOI, this function fails CLOSED and
 * returns `true` (i.e. "treat as a scan bomb, reject") rather than `false` ("not exceeding") -- see
 * the round-3 fix below for why "not exceeding" is never a safe default once the walk can no longer
 * make sense of the marker structure.
 *
 * Security-Audit fix (2026-09-27, MAJOR "scan-bomb fix bypassed by a single extraneous byte"): a
 * byte sitting where a marker was expected (i.e. not `0xFF`) does NOT make this function give up
 * and let `decode()` judge the file -- libjpeg's own `next_marker()` treats such a byte as
 * extraneous data, skips forward to the next `0xFF` and only WARNS (`JWRN_EXTRANEOUS_DATA`), and
 * the JDK reader treats that warning as non-fatal and decodes the file anyway. The walk below
 * mirrors that: it skips a single stray byte and keeps looking for the next marker instead of
 * bailing out, so a crafted file cannot hide its true SOS count behind one inserted byte (proven by
 * the `scan-bomb-extraneous-byte.jpg` fixture -- the original `scan-bomb.jpg` with one `0x00` byte
 * spliced in immediately before its first SOS marker, which the OLD implementation let straight
 * through as "not exceeding").
 *
 * Second follow-up Security-Audit fix (2026-09-27, MAJOR "scan-bomb gate still bypassable via a
 * bogus 2-byte length"): the round-2 fix above only handles a byte that is NOT `0xFF` where a marker
 * was expected. It left three other ways for the walk to give up and return `false` prematurely,
 * even though a real decoder tolerates all three and keeps decoding: (1) a generic segment (APPn,
 * COM, DQT, DHT, SOF, ... -- anything that is not EOI/TEM/a restart marker/SOS) whose declared
 * 2-byte length is `< 2`, i.e. shorter than the length field itself -- libjpeg's `save_marker`/
 * `skip_variable` explicitly comment this as "deal with bogus length word" and treat it as the
 * 2-byte minimum rather than erroring; (2) an SOS whose own header length is `< 2`, same story; (3)
 * a marker code of `0x00` (`0xFF 0x00`) sitting where a marker was expected -- that byte sequence is
 * legal ONLY inside entropy-coded scan data as a byte-stuffed literal `0xFF`, so at a marker
 * boundary it is nonsensical, and the OLD code fell into the generic-segment branch above and tried
 * to read a length field out of what is actually scan-adjacent data. All three let a single 4-byte
 * insertion right before a crafted file's first real SOS marker make the walk return `false`
 * regardless of the true scan count that follows -- proven against the real, compiled class with
 * `scan-bomb.jpg` (50 scans) turned into a 550-scan, 156,735-byte bomb via each of `FF FE 00 00`
 * (COM, length 0), `FF E1 00 01` (APP1, length 1) and `FF 00 00 00`; `process()` returned `Ok` after
 * ~25 s of blocking native decode for all three. The walk below now fails CLOSED on all three cases
 * (and, defense-in-depth, on any length field or SOS header that would run past the end of the byte
 * array) -- see the `scan-bomb-bogus-length-{com,app1,ff00}.jpg` fixtures and the corresponding
 * `ConferenceBackgroundImageProcessorTest` regressions, which assert `Undecodable` /
 * `decodeCalls == 0` for all three the same way the round-2 regression does for the single-byte
 * bypass.
 */
internal fun jpegScanCountExceeds(
    bytes: ByteArray,
    limit: Int,
): Boolean {
    if (bytes.size < 4 || (bytes[0].toInt() and 0xFF) != 0xFF || (bytes[1].toInt() and 0xFF) != 0xD8) {
        return false // No valid SOI -- not our job to reject this, the real decoder will.
    }
    var i = 2
    var scanCount = 0
    while (i < bytes.size) {
        if ((bytes[i].toInt() and 0xFF) != 0xFF) {
            // Extraneous byte where a marker was expected -- mirror libjpeg's next_marker(): skip
            // it and keep looking for the next real 0xFF marker byte, do NOT bail out (see KDoc
            // above, "scan-bomb fix bypassed by a single extraneous byte").
            i++
            continue
        }
        var j = i + 1
        while (j < bytes.size && (bytes[j].toInt() and 0xFF) == 0xFF) j++ // Skip 0xFF fill bytes before the marker byte.
        // Ran off the end while still looking for the marker byte after a genuine 0xFF -- the marker
        // structure is truncated. Fail closed (see KDoc "scan-bomb gate still bypassable via a bogus
        // 2-byte length"): unlike the stray-byte case above, this is not something a real decoder
        // tolerates and continues past, so there is no safe reason to say "not exceeding" here.
        if (j >= bytes.size) return true
        val marker = bytes[j].toInt() and 0xFF
        i = j + 1
        when {
            marker == 0xD9 -> return false // EOI -- end of image, no more scans to find.
            marker == 0x00 -> {
                // `0xFF 0x00` at a marker position: this is byte-stuffing syntax, legal ONLY inside
                // entropy-coded scan data as a literal 0xFF data byte -- never a real marker code.
                // Falling into the generic-segment branch below and reading a "length" out of what
                // is actually scan-adjacent data is exactly the round-3 bypass. Fail closed.
                return true
            }
            marker == 0x01 || marker in 0xD0..0xD7 -> {
                // TEM / restart markers outside scan data: standalone, no length field, no payload.
            }
            marker == 0xDA -> {
                scanCount++
                if (scanCount > limit) return true
                // Truncated SOS header length, a bogus (< 2, i.e. shorter than the length field
                // itself) header length, or a header length that runs past the end of the file are
                // all anomalies a real decoder (libjpeg's save_marker/skip_variable) tolerates and
                // keeps decoding past -- fail closed rather than silently under-counting scans from
                // here on (see KDoc "scan-bomb gate still bypassable via a bogus 2-byte length").
                if (i + 1 >= bytes.size) return true
                val headerLen = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                if (headerLen < 2 || i + headerLen > bytes.size) return true
                i += headerLen
                // Skip this scan's entropy-coded data until the NEXT real marker.
                while (i < bytes.size) {
                    if ((bytes[i].toInt() and 0xFF) == 0xFF) {
                        if (i + 1 >= bytes.size) return true // Truncated entropy data -- fail closed.
                        val next = bytes[i + 1].toInt() and 0xFF
                        when {
                            next == 0x00 -> i += 2 // Byte-stuffed literal 0xFF data byte.
                            next in 0xD0..0xD7 -> i += 2 // Restart marker -- entropy data continues.
                            next == 0xFF -> i += 1 // Fill byte -- keep scanning for the real marker byte.
                            else -> break // A real marker follows -- resume the outer loop from here.
                        }
                    } else {
                        i++
                    }
                }
            }
            else -> {
                // Same reasoning as the SOS header-length case above -- a bogus/truncated/
                // out-of-bounds generic segment length is exactly the round-3 bypass. Fail closed.
                if (i + 1 >= bytes.size) return true
                val segLen = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                if (segLen < 2 || i + segLen > bytes.size) return true
                i += segLen
            }
        }
    }
    return false
}

/**
 * Pure. Scales [width]x[height] down (NEVER up) so the longer edge is at most [maxLongEdge],
 * preserving aspect ratio. A source already within bounds is returned unchanged.
 */
internal fun backgroundTargetSize(
    width: Int,
    height: Int,
    maxLongEdge: Int,
): Pair<Int, Int> {
    val longEdge = maxOf(width, height)
    if (longEdge <= maxLongEdge) return width to height
    val scale = maxLongEdge.toDouble() / longEdge.toDouble()
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    return targetWidth to targetHeight
}
