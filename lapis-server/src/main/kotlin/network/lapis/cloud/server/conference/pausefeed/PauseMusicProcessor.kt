package network.lapis.cloud.server.conference.pausefeed

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.conference.FfmpegStepRunner
import network.lapis.cloud.shared.domain.PauseMediaRules
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/** The container formats the pause music may arrive in, with the demuxer that is FORCED for probing and transcoding. */
internal enum class SniffedAudio(
    val demuxer: String,
    /** The `format_name` ffprobe must report for this demuxer. */
    val expectedFormatName: String,
) {
    MP3("mp3", "mp3"),
    M4A("mov", "mov,mp4,m4a,3gp,3g2,mj2"),
    OGG("ogg", "ogg"),
    FLAC("flac", "flac"),
    WAV("wav", "wav"),
}

/** Magic-byte detection of the pause music. The client-declared type and the file name are never consulted. */
internal object AudioSniffer {
    private val M4A_BRANDS = setOf("M4A ", "M4B ", "mp42", "isom", "mp41", "dash")

    fun sniff(head: ByteArray): SniffedAudio? {
        if (head.size < 4) return null

        fun b(i: Int) = head[i].toInt() and 0xFF

        fun ascii(
            from: Int,
            len: Int,
        ) = if (head.size >= from + len) String(head, from, len, Charsets.ISO_8859_1) else ""
        return when {
            ascii(0, 3) == "ID3" -> SniffedAudio.MP3
            ascii(0, 4) == "OggS" -> SniffedAudio.OGG
            ascii(0, 4) == "fLaC" -> SniffedAudio.FLAC
            ascii(0, 4) == "RIFF" && ascii(8, 4) == "WAVE" -> SniffedAudio.WAV
            ascii(4, 4) == "ftyp" && ascii(8, 4) in M4A_BRANDS -> SniffedAudio.M4A
            // A bare MPEG audio frame: sync, a valid version, layer, bitrate and sample-rate index. Layer 00 is reserved and is exactly
            // what an ADTS (raw AAC) header looks like (FFF1/FFF9) -- ADTS is deliberately not accepted.
            head.size >= 3 &&
                b(0) == 0xFF &&
                (b(1) and 0xE0) == 0xE0 &&
                ((b(1) shr 3) and 3) != 1 &&
                ((b(1) shr 1) and 3) != 0 &&
                (b(2) shr 4) != 0xF &&
                (b(2) shr 4) != 0 &&
                ((b(2) shr 2) and 3) != 3 -> SniffedAudio.MP3
            else -> null
        }
    }
}

/** What ffprobe reported. */
internal data class AudioProbe(
    val formatName: String,
    val durationSeconds: Double?,
    val streams: List<AudioProbeStream>,
)

internal data class AudioProbeStream(
    val codecType: String?,
    val attachedPic: Boolean,
)

/** Test seam in front of ffprobe. */
internal fun interface AudioProber {
    /** `null` when ffprobe fails, times out or prints nothing usable. */
    suspend fun probe(
        file: File,
        demuxer: String,
    ): AudioProbe?
}

internal class FfprobeAudioProber(
    private val ffprobePath: String,
) : AudioProber {
    override suspend fun probe(
        file: File,
        demuxer: String,
    ): AudioProbe? =
        withContext(Dispatchers.IO) {
            val command =
                listOf(
                    ffprobePath,
                    "-v",
                    "error",
                    "-protocol_whitelist",
                    "file",
                    "-f",
                    demuxer,
                    "-show_entries",
                    "format=format_name,duration:stream=codec_type,codec_name:stream_disposition=attached_pic",
                    "-of",
                    "json",
                    file.path,
                )
            val process =
                try {
                    ProcessBuilder(command).redirectErrorStream(false).start()
                } catch (e: IOException) {
                    logger.warn { "ffprobe could not be started (${e::class.simpleName})" }
                    return@withContext null
                }
            runCatching { process.outputStream.close() }
            val stderrDrain =
                Thread { runCatching { process.errorStream.readNBytes(MAX_OUTPUT_BYTES) } }.apply {
                    isDaemon = true
                    start()
                }
            val stdout = StringBuilder()
            val reader =
                Thread {
                    runCatching {
                        val bytes = process.inputStream.readNBytes(MAX_OUTPUT_BYTES)
                        stdout.append(String(bytes, Charsets.UTF_8))
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(DRAIN_JOIN_MILLIS)
            stderrDrain.join(DRAIN_JOIN_MILLIS)
            if (!finished || process.exitValue() != 0) return@withContext null
            parse(stdout.toString())
        }

    internal fun parse(json: String): AudioProbe? =
        runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            val format = root["format"]?.jsonObject ?: return null
            val streams =
                root["streams"]
                    ?.jsonArray
                    ?.map { element ->
                        val o: JsonObject = element.jsonObject
                        AudioProbeStream(
                            codecType = (o["codec_type"] as? JsonPrimitive)?.contentOrNull,
                            attachedPic =
                                o["disposition"]
                                    ?.jsonObject
                                    ?.get("attached_pic")
                                    ?.jsonPrimitive
                                    ?.intOrNull == 1,
                        )
                    }.orEmpty()
            AudioProbe(
                formatName = format["format_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                durationSeconds = format["duration"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                streams = streams,
            )
        }.getOrNull()

    private companion object {
        const val TIMEOUT_SECONDS = 20L
        const val MAX_OUTPUT_BYTES = 1024 * 1024
        const val DRAIN_JOIN_MILLIS = 2_000L
    }
}

/** Outcome of [PauseMusicProcessor.process]. */
internal sealed interface MusicProcessingResult {
    class Ok(
        val file: File,
        val durationSeconds: Int,
    ) : MusicProcessingResult

    data object Unsupported : MusicProcessingResult

    data object TooShort : MusicProcessingResult

    data object TooLong : MusicProcessingResult

    data object HasVideo : MusicProcessingResult

    data object Failed : MusicProcessingResult
}

/**
 * V1.9.255 -- checks one uploaded audio file and re-encodes it into the stored pause music: AAC, 44.1 kHz, stereo, 128 kbit/s in an M4A
 * container, loudness-normalised with a short fade at both ends, no metadata (tags, cover pictures and chapters are dropped).
 *
 * The demuxer is FORCED to the sniffed container (`-f <demuxer>`) and the protocols are restricted to `file` for ffprobe AND ffmpeg, so a
 * crafted file (an HLS playlist or a concat script wearing an audio extension) can never make ffmpeg read another file or open a network
 * location. ffprobe's `format_name` must equal what the sniffed container implies. A real video stream is refused; the attached cover picture
 * of an MP3 (`attached_pic`) is allowed and dropped (`-map 0:a:0 -vn`).
 *
 * One transcode at a time (a [Semaphore] of one): the effort is bounded by the 90 s timeout and the 12 MB output cap.
 */
internal class PauseMusicProcessor(
    private val runner: FfmpegStepRunner,
    private val prober: AudioProber,
) {
    private val gate = Semaphore(1)

    suspend fun process(
        input: File,
        sniffed: SniffedAudio,
        out: File,
    ): MusicProcessingResult =
        gate.withPermit {
            val probe = prober.probe(file = input, demuxer = sniffed.demuxer) ?: return@withPermit MusicProcessingResult.Unsupported
            if (probe.formatName != sniffed.expectedFormatName) return@withPermit MusicProcessingResult.Unsupported
            if (probe.streams.none { it.codecType == "audio" }) return@withPermit MusicProcessingResult.Unsupported
            if (probe.streams.any { it.codecType == "video" && !it.attachedPic }) return@withPermit MusicProcessingResult.HasVideo
            val duration = probe.durationSeconds ?: return@withPermit MusicProcessingResult.Unsupported
            if (duration < PauseMediaRules.MUSIC_MIN_SECONDS) return@withPermit MusicProcessingResult.TooShort
            if (duration > PauseMediaRules.MUSIC_MAX_SECONDS) return@withPermit MusicProcessingResult.TooLong

            try {
                runner.run(
                    args = transcodeArgs(input = input, demuxer = sniffed.demuxer, out = out, durationSeconds = duration),
                    timeout = TRANSCODE_TIMEOUT,
                    label = "pause music transcode",
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                out.delete()
                throw e
            } catch (e: Exception) {
                logger.warn { "pause music transcode failed (${e::class.simpleName})" }
                out.delete()
                return@withPermit MusicProcessingResult.Failed
            }
            // Verify what we wrote, not what we meant to write.
            val written = prober.probe(file = out, demuxer = SniffedAudio.M4A.demuxer)
            val writtenDuration = written?.durationSeconds
            val ok =
                out.isFile &&
                    out.length() in 1..MAX_OUTPUT_BYTES &&
                    written != null &&
                    written.formatName == SniffedAudio.M4A.expectedFormatName &&
                    written.streams.any { it.codecType == "audio" } &&
                    written.streams.none { it.codecType == "video" } &&
                    writtenDuration != null &&
                    writtenDuration >= PauseMediaRules.MUSIC_MIN_SECONDS - 1 &&
                    writtenDuration <= PauseMediaRules.MUSIC_MAX_SECONDS + 1
            if (!ok) {
                out.delete()
                return@withPermit MusicProcessingResult.Failed
            }
            MusicProcessingResult.Ok(
                file = out,
                durationSeconds =
                    writtenDuration.roundToInt().coerceIn(
                        PauseMediaRules.MUSIC_MIN_SECONDS,
                        PauseMediaRules.MUSIC_MAX_SECONDS,
                    ),
            )
        }

    companion object {
        const val MAX_OUTPUT_BYTES = 12_000_000L
        private val TRANSCODE_TIMEOUT = 90.seconds
        private const val FADE_SECONDS = 0.5

        /** The re-encode arguments (also pinned by a test). `loudnorm` works at 192 kHz internally, hence the explicit `-ar` AFTER it. */
        internal fun transcodeArgs(
            input: File,
            demuxer: String,
            out: File,
            durationSeconds: Double,
        ): List<String> {
            val d = durationSeconds.coerceAtMost(PauseMediaRules.MUSIC_MAX_SECONDS.toDouble())
            val fadeOutStart = String.format(Locale.ROOT, "%.2f", (d - FADE_SECONDS).coerceAtLeast(0.0))
            val filter = "loudnorm=I=-20:TP=-2:LRA=11,afade=t=in:st=0:d=0.5,afade=t=out:st=$fadeOutStart:d=0.5"
            return listOf(
                "-nostdin",
                "-hide_banner",
                "-loglevel",
                "error",
                "-y",
                "-protocol_whitelist",
                "file",
                "-f",
                demuxer,
                "-i",
                input.path,
                "-map",
                "0:a:0",
                "-vn",
                "-sn",
                "-dn",
                "-map_metadata",
                "-1",
                "-map_chapters",
                "-1",
                "-t",
                PauseMediaRules.MUSIC_MAX_SECONDS.toString(),
                "-af",
                filter,
                "-c:a",
                "aac",
                "-b:a",
                "128k",
                "-ar",
                "44100",
                "-ac",
                "2",
                "-fs",
                MAX_OUTPUT_BYTES.toString(),
                "-movflags",
                "+faststart",
                "-f",
                "ipod",
                out.path,
            )
        }
    }
}

/**
 * The display label of an uploaded music file: only the base name (no path parts), control and bidirectional-formatting characters removed,
 * NFC, at most [PauseMediaRules.MUSIC_LABEL_MAX] characters; `"Pausenmusik"` if nothing is left. It is shown as TEXT only.
 */
internal fun sanitizeMusicLabel(raw: String?): String {
    if (raw.isNullOrBlank()) return DEFAULT_MUSIC_LABEL
    val base = raw.substringAfterLast('/').substringAfterLast('\\')
    val nfc = Normalizer.normalize(base, Normalizer.Form.NFC)
    val cleaned =
        buildString {
            for (ch in nfc) {
                val type = Character.getType(ch)
                val bidi = ch in '‪'..'‮' || ch in '⁦'..'⁩' || ch == '‎' || ch == '‏'
                if (ch.isISOControl() || type == Character.FORMAT.toInt() || bidi) continue
                append(ch)
            }
        }.replace("..", "").trim().take(PauseMediaRules.MUSIC_LABEL_MAX).trim()
    return cleaned.ifBlank { DEFAULT_MUSIC_LABEL }
}

private const val DEFAULT_MUSIC_LABEL = "Pausenmusik"
